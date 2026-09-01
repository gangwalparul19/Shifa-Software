package com.shifa.oms.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.CourierStatusApplier;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the two QuikShipX operations that are not courier-assignment:
 * <b>create-order</b> (on punch → their Pending section) and <b>confirm</b> (on
 * admin approval → mirror status to Confirmed). Both run out-of-band from the
 * {@link QuikShipXDrainer} so a slow/unavailable QuikShipX never blocks order
 * entry or approval.
 *
 * <p>The allot-tracking-id and track-order operations run through the existing
 * courier machinery via {@link QuikShipXCourierClient}; this service does not
 * touch them.
 */
@Service
public class QuikShipXService {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXService.class);

    private final QuikShipXProperties properties;
    private final QuikShipXClient client;
    private final QuikShipXPayloadFactory payloadFactory;
    private final OrderShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final CourierStatusApplier courierStatusApplier;
    private final OutboxEventPublisher outboxEventPublisher;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final OrderWorkflowService orderWorkflowService;

    public QuikShipXService(QuikShipXProperties properties,
                            QuikShipXClient client,
                            QuikShipXPayloadFactory payloadFactory,
                            OrderShipmentRepository shipmentRepository,
                            OrderRepository orderRepository,
                            ProductRepository productRepository,
                            AuditService auditService,
                            CourierStatusApplier courierStatusApplier,
                            OutboxEventPublisher outboxEventPublisher,
                            CourierRecordRepository courierRecordRepository,
                            CourierCompanyRepository courierCompanyRepository,
                            OrderWorkflowService orderWorkflowService) {
        this.properties = properties;
        this.client = client;
        this.payloadFactory = payloadFactory;
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.auditService = auditService;
        this.courierStatusApplier = courierStatusApplier;
        this.outboxEventPublisher = outboxEventPublisher;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.orderWorkflowService = orderWorkflowService;
    }

    /**
     * A live tracking view for an order: the current QuikShipX status, AWB, last
     * sync time, the scan timeline (newest first), and an optional message when
     * the shipment is not (yet) trackable.
     */
    public record TrackView(String quikShipXStatus, String awb, java.time.LocalDateTime lastSyncedAt,
                            java.util.List<QuikShipXModels.Scan> scans, String message) {
    }

    /**
     * Fetches live tracking for an order from QuikShipX and, when a status is
     * returned, mirrors it onto the shipment and applies the mapped order-status
     * transition (idempotent — only legal, system-authorised edges). Returns the
     * timeline for display. A not-yet-trackable shipment returns an empty timeline
     * with a message rather than throwing.
     *
     * @throws ResourceNotFoundException when the order has no QuikShipX shipment
     */
    @Transactional
    public TrackView trackLive(Long orderId) {
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no QuikShipX shipment yet."));
        String awb = shipment.getAwb();
        if (awb == null || awb.isBlank()) {
            return new TrackView(shipment.getQuikShipXStatus(), null, shipment.getLastSyncedAt(),
                    java.util.List.of(), "No tracking id assigned yet (dispatch the order to allot one).");
        }
        try {
            QuikShipXModels.TrackResult r = client.trackOrder(awb);
            shipment.recordTracked(r.orderStatus(), titleCase(r.orderStatus()), java.time.LocalDateTime.now());
            shipmentRepository.save(shipment);
            // Apply the mapped internal status transition (idempotent, SYSTEM).
            courierStatusApplier.applyByAwb(awb, r.orderStatus());
            return new TrackView(shipment.getQuikShipXStatus(), awb, shipment.getLastSyncedAt(),
                    r.scans(), null);
        } catch (QuikShipXException e) {
            // Not trackable yet / transient — surface cleanly, keep the last state.
            return new TrackView(shipment.getQuikShipXStatus(), awb, shipment.getLastSyncedAt(),
                    java.util.List.of(), e.getMessage());
        }
    }

    /** Title-cases a raw status like "out for delivery" -> "Out For Delivery". */
    private static String titleCase(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * Creates the QuikShipX shipment for a just-punched order (their Pending
     * section) and records an {@link OrderShipment}. Idempotent: a no-op when the
     * integration is off or a shipment already exists.
     *
     * @throws QuikShipXException on a QuikShipX error (the drainer retries)
     */
    @Transactional
    public void createForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (shipmentRepository.existsByOrderId(orderId)) {
            log.debug("QuikShipX shipment already exists for order {} — skipping create", orderId);
            return;
        }
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("QuikShipX create skipped: order {} no longer exists", orderId);
            return;
        }

        CreatePayload payload = payloadFactory.build(order, loadProducts(order));
        CreateResult result = client.createOrder(payload); // throws QuikShipXException on failure

        OrderShipment shipment = new OrderShipment(order.getId(), order.getOrderCode());
        shipment.recordCreated(result.shipperOrderId(), properties.isTestSecret());
        shipmentRepository.save(shipment);

        if (result.shipperOrderId() == null || result.shipperOrderId().isBlank()) {
            log.warn("QuikShipX created order {} but returned no shipper order id; a tracking id "
                    + "cannot be allotted until it is known (pin the response key)", order.getOrderCode());
        }
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_PUBLISHED,
                AuditActions.ENTITY_ORDER, String.valueOf(order.getId()),
                "Published order " + order.getOrderCode() + " to QuikShipX (Pending"
                        + (properties.isTestSecret() ? ", TEST" : "") + ")");
        log.info("Published order {} to QuikShipX (shipperOrderId={}, test={})",
                order.getOrderCode(), result.shipperOrderId(), properties.isTestSecret());
    }

    /**
     * Mirrors the QuikShipX status to Confirmed when an order is admin-approved.
     *
     * <p>QuikShipX does not (yet) document a confirm endpoint, so this updates the
     * local mirror only; when {@code app.quikshipx.confirm-order-path} and a
     * confirm operation become available they can be pushed here. If the shipment
     * has not been created yet (create still draining), a {@link QuikShipXException}
     * asks the drainer to retry until it exists.
     */
    @Transactional
    public void confirmForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            throw new QuikShipXException(
                    "QuikShipX shipment for order " + orderId + " not created yet; will retry confirm", true);
        }
        // Keep Confirmed as the floor; do not regress a shipment that already has
        // a tracking id (a re-run must not undo Tracking ID Assigned).
        if (shipment.getAwb() == null || shipment.getAwb().isBlank()) {
            shipment.setQuikShipXStatus(OrderShipment.STATUS_CONFIRMED);
            shipmentRepository.save(shipment);
        }
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_CONFIRMED,
                AuditActions.ENTITY_ORDER, String.valueOf(orderId),
                "Mirrored QuikShipX status to Confirmed for order " + shipment.getOrderCode());
        log.info("Mirrored QuikShipX status to Confirmed for order {}", shipment.getOrderCode());
        // Confirmed → Tracking ID Assigned: enqueue the allot out-of-band so the
        // Confirmed status sticks even if the allot needs retries (this row commits
        // in the drainer's transaction; the allot is idempotent).
        outboxEventPublisher.publishQuikShipXAllot(orderId, shipment.getOrderCode());
    }

    /**
     * Allots a QuikShipX tracking id (AWB) + label for a confirmed order (their
     * Tracking ID Assigned) and records a {@link CourierRecord} so the tracking
     * poller can follow it. Idempotent: a no-op when the integration is off, the
     * shipment already has an AWB, or the shipment is missing. Retries (throws
     * {@link QuikShipXException}) when the shipment/shipper-order-id is not ready.
     */
    @Transactional
    public void allotForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            throw new QuikShipXException(
                    "QuikShipX shipment for order " + orderId + " not created yet; will retry allot", true);
        }
        if (shipment.getAwb() != null && !shipment.getAwb().isBlank()) {
            return; // Already allotted — idempotent.
        }
        String shipperOrderId = shipment.getShipperOrderId();
        if (shipperOrderId == null || shipperOrderId.isBlank()) {
            log.warn("Cannot allot QuikShipX tracking id for order {}: no shipper order id "
                    + "(create response carried none)", shipment.getOrderCode());
            return; // Nothing to retry against.
        }
        AllotResult allot = client.allotTrackingId(shipperOrderId); // throws QuikShipXException on not-ready
        shipment.recordTrackingId(allot.awb(), allot.courierId(), allot.subCourierName(), allot.labelUrl());
        shipmentRepository.save(shipment);
        upsertCourierRecord(orderId, allot);
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_TRACKING_ALLOTTED,
                AuditActions.ENTITY_ORDER, String.valueOf(orderId),
                "Allotted QuikShipX AWB " + allot.awb() + " for order " + shipment.getOrderCode());
        log.info("Allotted QuikShipX AWB {} for order {} (courier {})",
                allot.awb(), shipment.getOrderCode(), allot.subCourierName());

        // Hand the order to the courier: fast-forward the internal status to
        // Courier_Assigned (SYSTEM), skipping the manual pack/handover/dispatch
        // steps — QuikShipX/Delhivery take it forward and the tracking poll drives
        // the rest. Only when the current status legally allows it (idempotent).
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getOrderStatus().canTransitionTo(OrderStatus.COURIER_ASSIGNED)) {
            orderWorkflowService.applyTransition(
                    order, OrderStatus.COURIER_ASSIGNED, Actor.system("QUIKSHIPX", "SYSTEM"));
            orderRepository.save(order);
            log.info("Order {} handed to QuikShipX courier (Courier_Assigned)", shipment.getOrderCode());
        }
    }

    /** Upserts the courier record (AWB + company) so the tracking poller follows the shipment. */
    private void upsertCourierRecord(Long orderId, AllotResult allot) {
        String name = (allot.subCourierName() == null || allot.subCourierName().isBlank())
                ? "QuikShipX" : allot.subCourierName();
        CourierCompany company = courierCompanyRepository.findFirstByName(name)
                .orElseGet(() -> courierCompanyRepository.save(
                        new CourierCompany(name, "https://www.delhivery.com/track/package/{awb}")));
        CourierRecord record = courierRecordRepository.findByOrderId(orderId)
                .orElseGet(() -> new CourierRecord(orderId));
        record.assign(company.getId(), allot.awb(), null, null);
        courierRecordRepository.save(record);
    }

    /** Batch-loads the products referenced by the order's lines (for SKU), avoiding N+1. */
    private Map<Long, Product> loadProducts(OrderEntity order) {
        List<Long> ids = order.getLineItems().stream()
                .map(OrderLineItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findAllById(ids)) {
            byId.put(product.getId(), product);
        }
        return byId;
    }
}
