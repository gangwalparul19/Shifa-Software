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
    private final com.shifa.oms.platform.outbox.OutboxEventRepository outboxEventRepository;

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
                            OrderWorkflowService orderWorkflowService,
                            com.shifa.oms.platform.outbox.OutboxEventRepository outboxEventRepository) {
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
        this.outboxEventRepository = outboxEventRepository;
    }

    /** The outcome of a per-order QuikShipX retry: what the action did. */
    public record RetryResult(boolean actioned, String message) {
    }

    /**
     * Admin per-order QuikShipX recovery (generalizes the Shopify-only recover to
     * any order — portal or Shopify). Pushes a stuck order forward depending on how
     * far it got:
     * <ul>
     *   <li>already has an AWB → no-op (nothing to recover);</li>
     *   <li>has FAILED QuikShipX outbox events → re-queues them to PENDING (fresh
     *       retry ladder) so the drainer retries the exact stuck step;</li>
     *   <li>has a shipment but no AWB and no pending/failed events → re-enqueues
     *       the allot (its {@code shipper_order_id} is already known);</li>
     *   <li>has no shipment at all → re-enqueues create → confirm → allot.</li>
     * </ul>
     * No-op (with a message) when the integration is disabled or the order is
     * flagged in-house. Idempotent and safe to call repeatedly.
     */
    @Transactional
    public RetryResult retryForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return new RetryResult(false, "QuikShipX integration is disabled.");
        }
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            throw new ResourceNotFoundException("Order " + orderId + " does not exist.");
        }
        if (order.isInHouseDelivery()) {
            return new RetryResult(false, "This order is flagged for in-house delivery (no QuikShipX).");
        }

        OrderShipment shipment = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (shipment != null && shipment.getAwb() != null && !shipment.getAwb().isBlank()) {
            return new RetryResult(false, "This order already has a QuikShipX tracking id ("
                    + shipment.getAwb() + ").");
        }

        // Re-queue any FAILED QuikShipX events for this order (the exact stuck step).
        List<com.shifa.oms.platform.outbox.OutboxEvent> events =
                outboxEventRepository.findByAggregateAndEventTypePrefix(
                        com.shifa.oms.platform.outbox.OutboxEvent.AGGREGATE_ORDER, orderId, "QUIKSHIPX");
        int requeued = 0;
        for (com.shifa.oms.platform.outbox.OutboxEvent e : events) {
            if (com.shifa.oms.platform.outbox.OutboxEvent.STATUS_FAILED.equals(e.getStatus())) {
                e.requeue();
                outboxEventRepository.save(e);
                requeued++;
            }
        }
        if (requeued > 0) {
            auditService.record(AuditActions.QUIKSHIPX_PUBLISHED, AuditActions.ENTITY_ORDER,
                    String.valueOf(orderId),
                    "Re-queued " + requeued + " failed QuikShipX event(s) for order " + order.getOrderCode());
            return new RetryResult(true, "Re-queued " + requeued
                    + " failed QuikShipX step(s); the tracking id will be allotted shortly.");
        }

        // No failed events to re-run — decide the right next step from the shipment.
        if (shipment == null) {
            outboxEventPublisher.publishQuikShipXCreate(orderId, order.getOrderCode());
            auditService.record(AuditActions.QUIKSHIPX_PUBLISHED, AuditActions.ENTITY_ORDER,
                    String.valueOf(orderId), "Re-published order " + order.getOrderCode()
                            + " to QuikShipX (create).");
            return new RetryResult(true, "Queued create → confirm → allot with QuikShipX.");
        }
        // Shipment exists, no AWB, nothing failed/pending — nudge the allot again.
        outboxEventPublisher.publishQuikShipXAllot(orderId, order.getOrderCode());
        auditService.record(AuditActions.QUIKSHIPX_PUBLISHED, AuditActions.ENTITY_ORDER,
                String.valueOf(orderId), "Re-queued QuikShipX allot for order " + order.getOrderCode());
        return new RetryResult(true, "Re-queued the tracking-id allotment with QuikShipX.");
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
     * <p>Tracks by AWB ({@code tracking_type=awb}) once a tracking id has been
     * allotted — confirmed working against the live QuikShipX API. Before an AWB
     * exists it falls back to QuikShipX's own order id ({@code tracking_type=order_id}),
     * which is typically not yet trackable but keeps the call harmless.
     *
     * @throws ResourceNotFoundException when the order has no QuikShipX shipment
     */
    @Transactional
    public TrackView trackLive(Long orderId) {
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no QuikShipX shipment yet."));
        String awb = shipment.getAwb();
        String shipperOrderId = shipment.getShipperOrderId();
        boolean haveAwb = awb != null && !awb.isBlank();
        if (!haveAwb && (shipperOrderId == null || shipperOrderId.isBlank())) {
            return new TrackView(shipment.getQuikShipXStatus(), shipment.getAwb(), shipment.getLastSyncedAt(),
                    java.util.List.of(), "Not published to QuikShipX yet.");
        }
        try {
            QuikShipXModels.TrackResult r = haveAwb ? client.trackOrder(awb) : client.trackOrderById(shipperOrderId);
            shipment.recordTracked(r.orderStatus(), titleCase(r.orderStatus()), java.time.LocalDateTime.now());
            shipmentRepository.save(shipment);
            // Apply the mapped internal status transition (idempotent, SYSTEM). The
            // AWB comes from the shipment, or the track response when not yet stored.
            String resolvedAwb = haveAwb ? awb : r.awb();
            if (resolvedAwb != null && !resolvedAwb.isBlank()) {
                courierStatusApplier.applyByAwb(resolvedAwb, r.orderStatus());
            }
            return new TrackView(shipment.getQuikShipXStatus(), resolvedAwb, shipment.getLastSyncedAt(),
                    r.scans(), null);
        } catch (QuikShipXException e) {
            // Not trackable yet / transient — surface cleanly, keep the last state.
            return new TrackView(shipment.getQuikShipXStatus(), shipment.getAwb(), shipment.getLastSyncedAt(),
                    java.util.List.of(), e.getMessage());
        }
    }

    /**
     * Defensive check: whether the order is flagged for in-house delivery, so a
     * stray/late CONFIRM or ALLOT outbox event never touches QuikShipX. A missing
     * order is treated as not in-house (falls through to the caller's own
     * not-found handling where applicable).
     */
    private boolean isInHouseDelivery(Long orderId) {
        return orderRepository.findById(orderId).map(OrderEntity::isInHouseDelivery).orElse(false);
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
        if (properties.isHttp() && !properties.hasCredentials()) {
            // Enabled + real API but the active user_secret (or client_code/user_id)
            // is blank — every call would be rejected by QuikShipX. Fail fast with an
            // actionable message instead of silently retrying a doomed request. The
            // common cause is setting QUIKSHIPX_USER_SECRET (unread) instead of
            // QUIKSHIPX_TEST_SECRET / QUIKSHIPX_LIVE_SECRET.
            throw new QuikShipXException("QuikShipX is enabled but credentials are incomplete for order "
                    + orderId + " (secretMode=" + properties.secretMode()
                    + "): check QUIKSHIPX_CLIENT_CODE / QUIKSHIPX_USER_ID / QUIKSHIPX_"
                    + (properties.isTestSecret() ? "TEST" : "LIVE") + "_SECRET", false);
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
        if (order.isInHouseDelivery()) {
            // Defensive: a stray/late outbox row for an order flagged in-house after
            // the enqueue site's own check — never publish it to QuikShipX.
            log.debug("QuikShipX create skipped: order {} is flagged for in-house delivery", order.getOrderCode());
            return;
        }

        Map<Long, Product> products = loadProducts(order);
        CreatePayload payload = payloadFactory.build(order, products);
        CreateResult result;
        try {
            result = client.createOrder(payload); // throws QuikShipXException on failure
        } catch (QuikShipXException e) {
            // Safety net: if QuikShipX still rejects the itemised amounts, resend the
            // same order as one consolidated line (amount == order_amount), which its
            // products-vs-order-amount check cannot reject.
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (!msg.contains("amount not matched")) {
                throw e;
            }
            log.warn("QuikShipX rejected itemised amounts for {}; retrying as a consolidated line",
                    order.getOrderCode());
            result = client.createOrder(payloadFactory.buildConsolidated(order, products));
        }

        OrderShipment shipment = new OrderShipment(order.getId(), order.getOrderCode());
        shipment.recordCreated(result.shipperOrderId(), properties.isTestSecret());
        shipmentRepository.save(shipment);

        // A successful (re)publish clears any prior permanent-failure reason so a
        // recovered order no longer shows the stale QuikShipX error.
        if (order.getQuikShipXFailureReason() != null) {
            order.setQuikShipXFailureReason(null);
            orderRepository.save(order);
        }

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
        if (!properties.isEnabled() || isInHouseDelivery(orderId)) {
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
        if (!properties.isEnabled() || isInHouseDelivery(orderId)) {
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
        // A successful allotment clears any prior permanent-failure reason.
        orderRepository.findById(orderId).ifPresent(o -> {
            if (o.getQuikShipXFailureReason() != null) {
                o.setQuikShipXFailureReason(null);
                orderRepository.save(o);
            }
        });
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_TRACKING_ALLOTTED,
                AuditActions.ENTITY_ORDER, String.valueOf(orderId),
                "Allotted QuikShipX AWB " + allot.awb() + " for order " + shipment.getOrderCode());
        log.info("Allotted QuikShipX AWB {} for order {} (courier {})",
                allot.awb(), shipment.getOrderCode(), allot.subCourierName());

        // Packing-workflow redesign: a QuikShipX order's tracking id + label are
        // allotted here (stored on the shipment above, status "Tracking ID
        // Assigned"), but the order's LIFECYCLE status is deliberately left at
        // Label_Generated so it still flows through the warehouse's manual packing
        // queue (Orders to Pack → Awaiting Handover → Handed to Delivery). The
        // COURIER_ASSIGNED ("Ready For Pickup") transition now happens on handover
        // (PackingService.handover), NOT automatically on allot. The old
        // fast-forward to COURIER_ASSIGNED has been removed so courier parcels are
        // physically packed + handed over before the courier takes them.
    }

    /**
     * Records a QuikShipX permanent-failure reason on the order (courier-failure
     * re-route feature) so it is visible on the order-detail drawer and the admin
     * can decide to re-route to in-house. Called by the drainer when a
     * create/confirm/allot event fails permanently. Best-effort: a missing order
     * is a no-op. Its own transaction so it is independent of the drainer's
     * outbox write.
     */
    @Transactional
    public void recordFailureReason(Long orderId, String reason) {
        orderRepository.findById(orderId).ifPresent(order -> {
            order.setQuikShipXFailureReason(reason);
            orderRepository.save(order);
        });
        // Also surface the failure as the shipment's DISPLAYED status, so the
        // Orders list + drawer show a clear "Shipping Failed" chip instead of a
        // misleading "Confirmed" — the admin/packer sees at a glance that the
        // order needs re-routing to in-house. Only when a shipment exists and no
        // tracking id has been allotted (a successful shipment is never downgraded).
        shipmentRepository.findByOrderId(orderId).ifPresent(shipment -> {
            if (shipment.getAwb() == null || shipment.getAwb().isBlank()) {
                shipment.recordFailed();
                shipmentRepository.save(shipment);
            }
        });
    }

    /**
     * Detaches an order from QuikShipX when an admin re-routes it to in-house
     * delivery (courier-failure re-route feature). Best-effort cleanup so the
     * failed/pending QuikShipX side leaves no loose ends:
     * <ol>
     *   <li>if a shipment exists, request its cancellation at QuikShipX (so the
     *       courier never collects it);</li>
     *   <li>mark any lingering FAILED/PENDING {@code QUIKSHIPX_*} outbox event for
     *       the order as resolved, so the self-heal re-drive never re-queues them
     *       (they would no-op anyway once the order is in-house, but this keeps the
     *       queue clean);</li>
     *   <li>clear the stored failure reason on the order.</li>
     * </ol>
     * Never throws — the OMS-side delivery-method switch is the source of truth and
     * must always succeed. A no-op when the integration is disabled.
     */
    @Transactional
    public void detachForInHouse(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            if (shipmentRepository.findByOrderId(orderId).isPresent()) {
                cancelForOrder(orderId); // best-effort, never throws
            }
            List<com.shifa.oms.platform.outbox.OutboxEvent> events =
                    outboxEventRepository.findByAggregateAndEventTypePrefix(
                            com.shifa.oms.platform.outbox.OutboxEvent.AGGREGATE_ORDER, orderId, "QUIKSHIPX");
            for (com.shifa.oms.platform.outbox.OutboxEvent e : events) {
                String status = e.getStatus();
                if (com.shifa.oms.platform.outbox.OutboxEvent.STATUS_FAILED.equals(status)
                        || com.shifa.oms.platform.outbox.OutboxEvent.STATUS_PENDING.equals(status)) {
                    e.markSent(); // resolve: no longer actionable (order is in-house)
                    outboxEventRepository.save(e);
                }
            }
            orderRepository.findById(orderId).ifPresent(order -> {
                if (order.getQuikShipXFailureReason() != null) {
                    order.setQuikShipXFailureReason(null);
                    orderRepository.save(order);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("QuikShipX detach-for-in-house cleanup for order {} failed (ignored): {}",
                    orderId, ex.getMessage());
        }
    }

    /** The outcome of a per-order QuikShipX cancellation attempt (order-cancellation feature). */
    public record CancelOutcome(boolean attempted, boolean accepted, String message) {
    }

    /**
     * Requests cancellation of an order's QuikShipX shipment so the courier is NOT
     * sent to pick the parcel up (order-cancellation feature). Called from the
     * admin cancel flow when a QuikShipX order is cancelled after being handed to
     * the courier.
     *
     * <p><b>Best-effort and never throws:</b> the OMS-side cancellation is the
     * source of truth and must always succeed, so any QuikShipX error (transport,
     * timeout, or a soft rejection) is caught and returned as a non-accepted
     * {@link CancelOutcome} for the audit trail / admin UI, not propagated. A no-op
     * (attempted=false) when the integration is off, the order is in-house, there
     * is no shipment, or no QuikShipX order id was ever captured. On acceptance the
     * shipment is stamped {@link OrderShipment#recordCancelled}.
     */
    @Transactional
    public CancelOutcome cancelForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return new CancelOutcome(false, false, "QuikShipX integration is disabled.");
        }
        if (isInHouseDelivery(orderId)) {
            return new CancelOutcome(false, false, "In-house delivery — no courier to cancel.");
        }
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            return new CancelOutcome(false, false, "No QuikShipX shipment exists for this order.");
        }
        String shipperOrderId = shipment.getShipperOrderId();
        if (shipperOrderId == null || shipperOrderId.isBlank()) {
            return new CancelOutcome(false, false,
                    "No QuikShipX order id was captured, so the courier cannot be told to cancel.");
        }
        if (shipment.isCancelled()) {
            return new CancelOutcome(false, true, "The QuikShipX shipment was already cancelled.");
        }
        try {
            QuikShipXModels.CancelResult result = client.cancelOrder(shipperOrderId);
            if (result.accepted()) {
                shipment.recordCancelled(java.time.LocalDateTime.now());
                shipmentRepository.save(shipment);
                auditService.record(AuditActions.QUIKSHIPX_CANCELLED, AuditActions.ENTITY_ORDER,
                        String.valueOf(orderId),
                        "Cancelled QuikShipX shipment " + shipperOrderId + " for order "
                                + shipment.getOrderCode() + " (courier pickup aborted)");
                log.info("Cancelled QuikShipX shipment {} for order {}", shipperOrderId, shipment.getOrderCode());
            } else {
                auditService.record(AuditActions.QUIKSHIPX_CANCELLED, AuditActions.ENTITY_ORDER,
                        String.valueOf(orderId),
                        "QuikShipX did not confirm cancellation of shipment " + shipperOrderId
                                + " for order " + shipment.getOrderCode() + ": " + result.message());
                log.warn("QuikShipX cancel not accepted for order {}: {}",
                        shipment.getOrderCode(), result.message());
            }
            return new CancelOutcome(true, result.accepted(), result.message());
        } catch (QuikShipXException e) {
            // Transport/timeout/parse error — the OMS cancel still succeeds; flag it.
            auditService.record(AuditActions.QUIKSHIPX_CANCELLED, AuditActions.ENTITY_ORDER,
                    String.valueOf(orderId),
                    "QuikShipX cancellation of shipment " + shipperOrderId + " for order "
                            + shipment.getOrderCode() + " could not be confirmed: " + e.getMessage());
            log.warn("QuikShipX cancel call failed for order {}: {}", shipment.getOrderCode(), e.getMessage());
            return new CancelOutcome(true, false, e.getMessage());
        }
    }

    /**
     * Upserts the courier record (AWB + company) so the tracking poller follows
     * the shipment. Always names the record's company {@code "QuikShipX"} — the
     * delivery partner actually selected — rather than the raw sub-courier
     * QuikShipX allots under the hood (e.g. a mocked/real "Direct_Delhivery"),
     * which is an internal routing detail meaningless to our own team and would
     * otherwise surface verbatim on the order detail / label ("COURIER:
     * DIRECT_DELIVERY").
     */
    private void upsertCourierRecord(Long orderId, AllotResult allot) {
        String name = "QuikShipX";
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
