package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.OrderStatusLifecycle;
import com.shifa.oms.statemachine.OrderStatusStateMachine;
import com.shifa.oms.statemachine.StatusHistoryEntry;
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
    private static final String STORAGE_PREFIX = "labels/shipping";

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final CourierClient courierClient;
    private final ShippingLabelService shippingLabelService;
    private final StorageService storageService;
    private final CourierProperties properties;
    private final OrderStatusStateMachine stateMachine = new OrderStatusStateMachine();

    public CourierAssignmentService(OrderRepository orderRepository,
                                    CourierRecordRepository courierRecordRepository,
                                    CourierCompanyRepository courierCompanyRepository,
                                    CourierClient courierClient,
                                    ShippingLabelService shippingLabelService,
                                    StorageService storageService,
                                    CourierProperties properties) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.courierClient = courierClient;
        this.shippingLabelService = shippingLabelService;
        this.storageService = storageService;
        this.properties = properties;
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

        if (order.getOrderStatus() != OrderStatus.PACKED) {
            // Idempotent no-op: order already advanced (or not yet packed).
            log.debug("Skipping courier assignment for order {} in status {}",
                    order.getOrderCode(), order.getOrderStatus());
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

        // Advance Packed → Courier_Assigned (Req 12.2), recording one history row (Req 8.4).
        applyTransition(order, OrderStatus.COURIER_ASSIGNED);
        orderRepository.save(order);

        log.debug("Assigned AWB {} to order {} (courier {})",
                result.awb(), order.getOrderCode(), company.getName());
    }

    private CourierCompany resolveCompany(String courierName) {
        String name = (courierName == null || courierName.isBlank())
                ? properties.companyName() : courierName;
        return courierCompanyRepository.findFirstByName(name)
                .orElseGet(() -> courierCompanyRepository.save(
                        new CourierCompany(name, "https://track.example.com/{awb}")));
    }

    private void applyTransition(OrderEntity order, OrderStatus target) {
        OrderStatusLifecycle lifecycle = new OrderStatusLifecycle(order.getOrderStatus());
        StatusHistoryEntry entry = stateMachine.transition(lifecycle, target, "COURIER_API", SOURCE_SYSTEM);
        order.setOrderStatus(entry.toStatus());
        order.addStatusHistory(new OrderStatusHistory(
                entry.fromStatus(), entry.toStatus(), entry.actor(), entry.source()));
    }
}
