package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests an AWB + shipping label from the {@link CourierClient} for a packed
 * order and, on success, records the shipment and advances the order to
 * {@code Courier_Assigned} (Req 12.1, 12.2, 12.3).
 *
 * <p>Invoked out-of-band by the {@link OutboxCourierDrainer}, not on the
 * synchronous packing path, so a slow/unavailable courier never blocks packing.
 * The whole method is one transaction: on a courier error/timeout it throws
 * {@link CourierClientException}, the transaction rolls back, and the order
 * <em>retains</em> {@code Packed} (Req 12.4) — the drainer then records the
 * failure and, once retries are exhausted, the admin notification.
 *
 * <p>Idempotent: if the order is no longer {@code Packed} (e.g. a duplicate
 * event, or a record already assigned), the method returns without re-calling
 * the courier.
 */
@Service
public class CourierAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(CourierAssignmentService.class);
    private static final String SOURCE_SYSTEM = "SYSTEM";
    private static final String ACTOR_COURIER_API = "COURIER_API";
    private static final String STORAGE_PREFIX = "labels/shipping";

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final CourierClient courierClient;
    private final ShippingLabelService shippingLabelService;
    private final StorageService storageService;
    private final CourierProperties properties;
    private final OrderWorkflowService orderWorkflowService;

    public CourierAssignmentService(OrderRepository orderRepository,
                                    CourierRecordRepository courierRecordRepository,
                                    CourierCompanyRepository courierCompanyRepository,
                                    CourierClient courierClient,
                                    ShippingLabelService shippingLabelService,
                                    StorageService storageService,
                                    CourierProperties properties,
                                    OrderWorkflowService orderWorkflowService) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.courierClient = courierClient;
        this.shippingLabelService = shippingLabelService;
        this.storageService = storageService;
        this.properties = properties;
        this.orderWorkflowService = orderWorkflowService;
    }

    /**
     * Attempts courier assignment for an order (Req 12.1-12.3).
     *
     * @param orderId the packed order's id
     * @throws CourierClientException on a courier error/timeout (retryable; order retains Packed)
     */
    @Transactional
    public void assignForOrder(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getOrderStatus() != OrderStatus.HANDED_TO_DELIVERY) {
            // Idempotent no-op: order already advanced (or not yet handed over).
            // Courier assignment now runs from Handed_To_Delivery (dispatch), not
            // directly from Packed (design §4.1).
            log.debug("Skipping courier assignment for order {} in status {}",
                    order.getOrderCode(), order.getOrderStatus());
            return;
        }

        if (order.isInHouseDelivery()) {
            // In-house delivery has no courier partner at all, so there is no AWB
            // to request: Shifa's own team carries the parcel and staff advance the
            // status by hand (ManualDeliveryService). Without this guard the
            // courier client would mint a meaningless AWB for an in-house order
            // and it would surface on the order/label as if a courier had one
            // (in-house-delivery feature).
            log.debug("Skipping courier assignment for in-house order {}", order.getOrderCode());
            return;
        }

        CourierAssignmentRequest request = new CourierAssignmentRequest(
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                order.getCodAmount());

        // May throw CourierClientException → transaction rolls back, order stays Packed (Req 12.4).
        CourierAssignmentResult result = courierClient.assign(request);
        if (result == null || result.awb() == null || result.awb().isBlank()) {
            throw new CourierClientException(
                    "Courier returned no AWB for order " + order.getOrderCode() + ".");
        }

        CourierCompany company = resolveCompany(result.courierName());
        String trackingUrl = company.trackingUrl(result.awb());

        // Render and store the courier shipping label PDF (Req 12.3).
        byte[] labelPdf = shippingLabelService.render(
                shippingLabelService.buildContent(order, result.awb(), company.getName(), trackingUrl));
        StorageService.StoredObjectRef ref = storageService.store(
                STORAGE_PREFIX, order.getOrderCode() + ".pdf", "application/pdf", labelPdf);

        // Create or update the courier record with the AWB, company, ETA, label (Req 12.2).
        CourierRecord record = courierRecordRepository.findByOrderId(orderId)
                .orElseGet(() -> new CourierRecord(orderId));
        record.assign(company.getId(), result.awb(), ref.key(), result.estimatedDelivery());
        courierRecordRepository.save(record);

        // Advance Handed_To_Delivery → Courier_Assigned (Req 10.1), recording one
        // history row (Req 12.6) through the central workflow service as the
        // automatic SYSTEM actor. On a courier error above, the transaction rolls
        // back and the order retains Handed_To_Delivery for retry (Req 10.4).
        orderWorkflowService.applyTransition(order, OrderStatus.COURIER_ASSIGNED,
                Actor.system(ACTOR_COURIER_API, SOURCE_SYSTEM));
        orderRepository.save(order);

        log.debug("Assigned AWB {} to order {} (courier {})",
                result.awb(), order.getOrderCode(), company.getName());
    }

    /**
     * Manually attaches a courier name + AWB to an order (enhancement:
     * "assign courier early"). Unlike {@link #assignForOrder}, this does not
     * require the order to be {@code Handed_To_Delivery} and does not advance
     * its status — it simply records/updates the {@link CourierRecord} so the
     * internal label's courier barcode can render as soon as staff know the
     * courier + AWB (e.g. booked at a courier counter before dispatch), instead
     * of waiting for the automatic in-house assignment that only runs after
     * dispatch. Idempotent: calling again with a different AWB/courier updates
     * the existing record for the order.
     *
     * <p>The AWB is <strong>optional</strong> (in-house-delivery feature): an
     * in-house delivery, or a parcel handed to a local operator / bus / train,
     * has no tracking number. With no AWB the label simply falls back to our own
     * order-code barcode (which the packing/RTO scan resolves), so the parcel
     * stays scannable end-to-end.
     *
     * @param orderId     the order id
     * @param courierName the courier partner's display name (required, matched/created by name)
     * @param awb         the AWB / tracking number, or {@code null}/blank when there is none
     * @throws com.shifa.oms.common.ResourceNotFoundException when the order doesn't exist
     * @throws com.shifa.oms.common.ValidationException       when courierName is blank
     */
    @Transactional
    public void manuallyAssign(Long orderId, String courierName, String awb) {
        if (courierName == null || courierName.isBlank()) {
            throw new com.shifa.oms.common.ValidationException("Courier name is required.");
        }
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        String trimmedAwb = (awb == null || awb.isBlank()) ? null : awb.trim();
        CourierCompany company = resolveCompany(courierName.trim());
        CourierRecord record = courierRecordRepository.findByOrderId(orderId)
                .orElseGet(() -> new CourierRecord(orderId));
        record.assign(company.getId(), trimmedAwb, record.getShippingLabelKey(),
                record.getEstimatedDelivery());
        courierRecordRepository.save(record);

        log.debug("Manually assigned courier {} (AWB {}) to order {}",
                company.getName(), trimmedAwb == null ? "none" : trimmedAwb, order.getOrderCode());
    }

    private CourierCompany resolveCompany(String courierName) {
        String name = (courierName == null || courierName.isBlank())
                ? properties.companyName() : courierName;
        // No carrier tracking URL — QuikShipX (aggregator) tracking is via its API,
        // not a per-carrier public page, so we don't build a carrier link.
        return courierCompanyRepository.findFirstByName(name)
                .orElseGet(() -> courierCompanyRepository.save(new CourierCompany(name, null)));
    }

    /**
     * The known delivery partners, alphabetical (delivery-partner dropdown
     * enhancement) — backs the "Assign courier" modal's picker.
     */
    @Transactional(readOnly = true)
    public java.util.List<CourierCompany> listCompanies() {
        return courierCompanyRepository.findAllByOrderByNameAsc();
    }
}
