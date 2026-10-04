package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.quikshipx.QuikShipXProperties;
import com.shifa.oms.quikshipx.QuikShipXService;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin order-approval application service (Req 9).
 *
 * <p>Builds the approval queue of {@code Pending_Admin_Approval} orders with the
 * review details the admin needs on screen (Req 9.1, 9.2), and performs the
 * approve (Req 9.3) and reject (Req 9.4) actions. Every status change is routed
 * through the central {@link OrderWorkflowService} so authorization, legality,
 * history, and audit live in one place: approving/rejecting an order that is not
 * pending is rejected with a {@code 409} and the order's status is retained
 * (Req 8.3). Each accepted transition appends exactly one
 * {@link OrderStatusHistory} row (Req 8.4).
 *
 * <p>Rejection requires a non-blank reason; the reason is validated at the DTO
 * boundary (400 when missing/blank) and is stored on the order (Req 9.4).
 */
@Service
public class AdminOrderService {

    private static final String SOURCE_ADMIN = "ADMIN";

    /** {@code vouchers.source_type} for a finalised sales order (matches {@code SourceType.ORDER}). */
    private static final String LEDGER_SOURCE_ORDER = "ORDER";

    private final OrderRepository orderRepository;
    private final LabelService labelService;
    private final OrderWorkflowService orderWorkflowService;
    private final OutboxEventPublisher outboxEventPublisher;
    /**
     * QuikShipX approval hook (nullable): when the integration is enabled, a
     * {@code QUIKSHIPX_CONFIRM} outbox event is enqueued on approval so the
     * shipment's QuikShipX status is mirrored to Confirmed. Null in unit tests
     * that use the legacy constructor — the hook is then skipped.
     */
    private final QuikShipXProperties quikShipXProperties;
    /** QuikShipX shipment mirror (nullable): enriches the Orders list with the QuikShipX status chip. */
    private final OrderShipmentRepository orderShipmentRepository;

    /**
     * Staff directory (nullable): resolves each order's {@code created_by} to the
     * salesperson's display name so the Orders list and Approval Queue show who
     * punched each order. Null under the legacy test constructor — the name is
     * then simply omitted.
     */
    private final UserRepository userRepository;

    /**
     * QuikShipX cancel hook (nullable): when an admin cancels a QuikShipX order
     * that was already handed to the courier, this requests cancellation at
     * QuikShipX so the courier aborts the pickup (order-cancellation feature).
     * Null under the legacy test constructor — the courier cancel is then skipped
     * (the OMS-side cancellation still happens).
     */
    private final QuikShipXService quikShipXService;

    /**
     * Audit trail (nullable): records the cancellation (with the courier-cancel
     * outcome) on the order's audit history. Null under the legacy test constructor.
     */
    private final AuditService auditService;

    /**
     * Duplicate-proof lookup (nullable): resolves the other order codes whose
     * payment proof is byte-identical to a given order's (V72), so the approval
     * queue can show a duplicate flag and exclude such orders from "approve all".
     * Null under the legacy test constructor — the flag is then always empty.
     */
    private final OrderPaymentScreenshotRepository screenshotRepository;

    /** Legacy constructor (unit tests): no QuikShipX hooks / list enrichment / audit. */
    public AdminOrderService(OrderRepository orderRepository, LabelService labelService,
                             OrderWorkflowService orderWorkflowService,
                             OutboxEventPublisher outboxEventPublisher) {
        this(orderRepository, labelService, orderWorkflowService, outboxEventPublisher,
                null, null, null, null, null, null);
    }

    @Autowired
    public AdminOrderService(OrderRepository orderRepository, LabelService labelService,
                             OrderWorkflowService orderWorkflowService,
                             OutboxEventPublisher outboxEventPublisher,
                             QuikShipXProperties quikShipXProperties,
                             OrderShipmentRepository orderShipmentRepository,
                             UserRepository userRepository,
                             QuikShipXService quikShipXService,
                             AuditService auditService,
                             OrderPaymentScreenshotRepository screenshotRepository) {
        this.orderRepository = orderRepository;
        this.labelService = labelService;
        this.orderWorkflowService = orderWorkflowService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.quikShipXProperties = quikShipXProperties;
        this.orderShipmentRepository = orderShipmentRepository;
        this.userRepository = userRepository;
        this.quikShipXService = quikShipXService;
        this.auditService = auditService;
        this.screenshotRepository = screenshotRepository;
    }

    /**
     * Server-side paged / sorted / filtered admin orders listing that backs the
     * Wave 2 orders table (ROADMAP 2.2). Every filter is optional; the result is
     * a page of compact {@link OrderSummaryResponse} rows in the caller-specified
     * sort order (default: newest first, applied by the controller's Pageable).
     *
     * @param q             substring over order code / customer name / mobile (nullable)
     * @param status        exact order lifecycle status (nullable)
     * @param paymentStatus exact payment status (nullable)
     * @param from          inclusive lower bound on {@code created_at} date (nullable)
     * @param to            inclusive upper bound on {@code created_at} date (nullable)
     * @param pageable      page / size / sort
     * @return a page of order summaries
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(String q, OrderStatus status,
                                                 PaymentStatus paymentStatus,
                                                 LocalDate from, LocalDate to,
                                                 Pageable pageable) {
        return listOrders(q, status, paymentStatus, from, to, pageable, (Long) null);
    }

    /**
     * As {@link #listOrders(String, OrderStatus, PaymentStatus, LocalDate, LocalDate, Pageable)}
     * but scoped to a single creator when {@code createdBy} is non-null. A
     * salesperson passes their own user id so the orders table shows only the
     * orders they punched (across every lifecycle status — their history);
     * admins/accountants pass {@code null} to see all orders.
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(String q, OrderStatus status,
                                                 PaymentStatus paymentStatus,
                                                 LocalDate from, LocalDate to,
                                                 Pageable pageable, Long createdBy) {
        return listOrders(q, status, null, paymentStatus, from, to, pageable, createdBy);
    }

    /**
     * As {@link #listOrders(String, OrderStatus, PaymentStatus, LocalDate, LocalDate, Pageable, Long)}
     * with an additional coarse {@link OrderStatusGroup} filter. When
     * {@code statusGroup} is non-null the result is restricted to the group's
     * member statuses ({@code orderStatus IN (...)}), letting the Orders page
     * offer a small set of business-facing stages instead of the full raw
     * status list.
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(String q, OrderStatus status,
                                                 OrderStatusGroup statusGroup,
                                                 PaymentStatus paymentStatus,
                                                 LocalDate from, LocalDate to,
                                                 Pageable pageable, Long createdBy) {
        return listOrders(q, status, statusGroup, paymentStatus, from, to, pageable,
                createdBy == null ? null : java.util.List.of(createdBy));
    }

    /**
     * Canonical paged listing scoped to a <em>set</em> of creators (team-aware):
     * a {@code SALESPERSON} passes a singleton of their own id, a {@code TEAM_LEAD}
     * passes their team's salesperson ids, and an unscoped admin/accountant passes
     * {@code null}. A present-but-empty collection matches no rows.
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(String q, OrderStatus status,
                                                 OrderStatusGroup statusGroup,
                                                 PaymentStatus paymentStatus,
                                                 LocalDate from, LocalDate to,
                                                 Pageable pageable, java.util.Collection<Long> creatorIds) {
        return listOrders(q, status, statusGroup, paymentStatus, from, to, pageable, creatorIds, null);
    }

    /**
     * As the {@code creatorIds} canonical listing with an additional exact
     * {@link com.shifa.oms.order.OrderSource} filter (e.g. only Shopify-imported
     * orders). {@code null} source = no source filter.
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listOrders(String q, OrderStatus status,
                                                 OrderStatusGroup statusGroup,
                                                 PaymentStatus paymentStatus,
                                                 LocalDate from, LocalDate to,
                                                 Pageable pageable, java.util.Collection<Long> creatorIds,
                                                 com.shifa.oms.order.OrderSource source) {
        Specification<OrderEntity> spec =
                OrderListSpecifications.build(q, status, statusGroup, paymentStatus, from, to, creatorIds, source);
        Page<OrderEntity> entities = orderRepository.findAll(spec, pageable);
        // Resolve each row's salesperson (created_by) name once for the whole page,
        // so the Orders table shows who punched each order without an N+1.
        Map<Long, String> names = resolveSalespersonNames(entities.getContent());
        Page<OrderSummaryResponse> page = entities.map(order -> OrderSummaryResponse.from(order)
                .withSalesperson(order.getCreatedBy() == null ? null : names.get(order.getCreatedBy())));
        return enrichWithQuikShipStatus(page);
    }

    /**
     * Batch-resolves the display names of the salespeople who created the given
     * orders (their {@code created_by}), mirroring the packing/reporting pattern:
     * one {@code findAllById} query, full name when set, else username. Returns an
     * empty map when there is no staff directory (legacy test constructor) or no
     * creators to resolve, so the name is simply omitted.
     */
    private Map<Long, String> resolveSalespersonNames(java.util.Collection<OrderEntity> orders) {
        Map<Long, String> names = new java.util.HashMap<>();
        if (userRepository == null) {
            return names;
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (OrderEntity o : orders) {
            if (o.getCreatedBy() != null) {
                ids.add(o.getCreatedBy());
            }
        }
        if (ids.isEmpty()) {
            return names;
        }
        for (com.shifa.oms.auth.User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    /**
     * Batch-loads each page order's QuikShipX status (one query) and attaches it to
     * the summaries so the Orders list can render a QuikShipX chip — avoiding an
     * N+1. No-op when the integration mirror is unavailable or the page is empty.
     */
    private Page<OrderSummaryResponse> enrichWithQuikShipStatus(Page<OrderSummaryResponse> page) {
        if (orderShipmentRepository == null || page.isEmpty()) {
            return page;
        }
        List<Long> ids = page.getContent().stream()
                .map(OrderSummaryResponse::id)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return page;
        }
        java.util.Map<Long, com.shifa.oms.quikshipx.OrderShipment> byOrderId = new java.util.HashMap<>();
        orderShipmentRepository.findByOrderIdIn(ids)
                .forEach(s -> byOrderId.put(s.getOrderId(), s));
        if (byOrderId.isEmpty()) {
            return page;
        }
        return page.map(row -> {
            com.shifa.oms.quikshipx.OrderShipment s = byOrderId.get(row.id());
            return s == null ? row
                    : row.withQuikShip(s.getQuikShipXStatus(), s.getShipperOrderId(), s.getAwb());
        });
    }

    /** Approval queue: all pending-approval orders with review details (Req 9.1, 9.2). */
    @Transactional(readOnly = true)
    public List<ApprovalQueueItemResponse> approvalQueue() {
        List<OrderEntity> pending = orderRepository
                .findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PENDING_ADMIN_APPROVAL);
        Map<Long, String> names = resolveSalespersonNames(pending);
        return pending.stream()
                .map(o -> ApprovalQueueItemResponse.from(
                        o, o.getCreatedBy() == null ? null : names.get(o.getCreatedBy()),
                        duplicateOrderCodes(o)))
                .toList();
    }

    /**
     * Other order codes whose payment proof is byte-identical to this order's
     * (duplicate-screenshot detection, V72), so the approval queue can flag a
     * suspected duplicate and exclude it from "approve all (no duplicates)".
     * Empty when there is no screenshot repository (test/legacy), no proof hash,
     * or the proof is unique. Mirrors {@code PaymentVerificationService.duplicateOrderCodes}.
     */
    private List<String> duplicateOrderCodes(OrderEntity order) {
        if (screenshotRepository == null || order.getId() == null) {
            return List.of();
        }
        List<String> hashes = screenshotRepository.findHashesForOrder(order.getId());
        if (hashes.isEmpty()) {
            return List.of();
        }
        java.util.Set<Long> otherIds = new java.util.HashSet<>();
        for (String hash : hashes) {
            otherIds.addAll(screenshotRepository.findOtherOrderIdsWithHash(hash, order.getId()));
        }
        if (otherIds.isEmpty()) {
            return List.of();
        }
        return orderRepository.findAllById(otherIds).stream()
                .map(OrderEntity::getOrderCode)
                .filter(code -> code != null && !code.isBlank())
                .sorted()
                .toList();
    }

    /**
     * Payment-verification gate for approval (payment-verification-gated
     * approval): an order whose payment is still PENDING or was REJECTED cannot
     * be approved — the admin must verify the payment first (via the payment
     * panel / the approval-queue verify action). A pure-COD order
     * ({@code paymentVerificationStatus == null}) and an already-VERIFIED order
     * pass. Throws a 400 {@link ValidationException} otherwise, naming the order.
     */
    private static void requirePaymentVerified(OrderEntity order) {
        PaymentVerificationStatus status = order.getPaymentVerificationStatus();
        if (status == PaymentVerificationStatus.PENDING || status == PaymentVerificationStatus.REJECTED) {
            throw new ValidationException("Order " + order.getOrderCode()
                    + " cannot be approved until its payment is verified.");
        }
    }

    /**
     * Approves a pending order (Req 9.3): {@code Pending_Admin_Approval → Approved}
     * via the state machine, with the acting admin recorded as the actor. Rejected
     * with 409 if the order is not in a state from which approval is legal.
     *
     * <p>Per Req 10.1-10.3, approval immediately triggers internal company label
     * generation: within the same transaction the {@link LabelService} renders and
     * stores the label PDF and advances the order {@code Approved → Label_Generated}.
     * The returned order therefore reflects the {@code Label_Generated} status and
     * carries both status-history rows (approval + label generation).
     */
    @Transactional
    public OrderResponse approve(Long id, AuthPrincipal admin) {
        return approve(id, admin, null);
    }

    /**
     * Approves a pending order, optionally setting/overriding its delivery
     * method (in-house-delivery feature): the admin decides at approval time
     * whether the order goes out via QuikShipX or Shifa's own in-house team,
     * regardless of the default recorded at order entry. A {@code null}/blank
     * {@code deliveryMethod} leaves the order's current value unchanged (Req 9.3).
     *
     * @param deliveryMethod {@code "QUIKSHIPX"} or {@code "IN_HOUSE"} (case-insensitive),
     *                       or {@code null}/blank to leave unchanged
     */
    @Transactional
    public OrderResponse approve(Long id, AuthPrincipal admin, String deliveryMethod) {
        OrderEntity order = requireOrder(id);
        // Payment-verification gate: an order with an unverified / rejected
        // payment cannot be approved — the payment must be verified first.
        requirePaymentVerified(order);
        if (deliveryMethod != null && !deliveryMethod.isBlank()) {
            DeliveryMethod requested =
                    DeliveryMethod.valueOf(deliveryMethod.trim().toUpperCase(java.util.Locale.ROOT));
            // Counter Sale (walk-in shop order) never goes through a delivery
            // partner — reject any attempt to override it to QuikShipX at
            // approval time rather than silently ignoring the admin's choice.
            if (order.getLeadSource() == LeadSource.COUNTER_SALE && requested != DeliveryMethod.IN_HOUSE) {
                throw new com.shifa.oms.common.ValidationException(
                        "This order is a Counter Sale and cannot be assigned a delivery partner.");
            }
            order.setDeliveryMethod(requested);
        }
        orderWorkflowService.applyTransition(
                order, OrderStatus.APPROVED, Actor.user(admin, SOURCE_ADMIN));
        // Req 10.1-10.3: generate the internal label and move to Label_Generated.
        labelService.generateInternalLabelOnApproval(order, admin.username());
        OrderEntity saved = orderRepository.save(order);
        // Auto-posting (Reqs 8.1, 8.3, 17.3, 17.4): enqueue a ledger-post event in this same
        // transaction so the General Ledger derives the balanced Sales voucher out-of-band. The
        // event row commits atomically with the approval; a downstream posting failure can never
        // roll back or alter this order (additive — no change to existing behaviour/return value).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_ORDER, saved.getId());
        // QuikShipX (confirm-on-approve): enqueue a Confirmed status mirror in this
        // same transaction when the integration is enabled AND the order is not
        // flagged for in-house delivery (no-op otherwise) — an in-house order never
        // touches the QuikShipX pipeline.
        if (quikShipXProperties != null && quikShipXProperties.isEnabled() && !saved.isInHouseDelivery()) {
            outboxEventPublisher.publishQuikShipXConfirm(saved.getId(), saved.getOrderCode());
        }
        return OrderResponse.from(saved);
    }

    /**
     * Pre-dispatch lifecycle stages in which an admin may change the delivery
     * method without approving/dispatching. Delivery method only matters until
     * the parcel is handed to a courier / dispatched; once {@code HANDED_TO_DELIVERY}
     * or later the shipment path is committed and must not change.
     */
    private static final java.util.Set<OrderStatus> DELIVERY_METHOD_EDITABLE_STATUSES =
            java.util.EnumSet.of(
                    OrderStatus.PENDING_ADMIN_APPROVAL,
                    OrderStatus.APPROVED,
                    OrderStatus.LABEL_GENERATED,
                    OrderStatus.PACKED);

    /**
     * Admin "save delivery method" (change-delivery-method feature): sets the
     * order's delivery partner ({@code QUIKSHIPX} or {@code IN_HOUSE}) and
     * persists it WITHOUT approving or otherwise changing the order's lifecycle
     * status. Previously the only way to persist a delivery-method choice was
     * through {@link #approve}; this lets an admin correct the method on a
     * still-pending order and save it on its own.
     *
     * <p>Only allowed while the order is pre-dispatch
     * ({@link #DELIVERY_METHOD_EDITABLE_STATUSES}); a 409 ({@code OrderNotEditableException})
     * is returned once the parcel has been handed to a courier / dispatched. A
     * Counter Sale (walk-in shop order) can never be assigned a courier partner —
     * trying to set it to QUIKSHIPX is a 400, matching the guard in {@link #approve}.
     * Does NOT trigger label generation, QuikShipX publish, or any status change.
     */
    @Transactional
    public OrderResponse updateDeliveryMethod(Long id, String deliveryMethod, AuthPrincipal admin) {
        OrderEntity order = requireOrder(id);
        if (!DELIVERY_METHOD_EDITABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new OrderNotEditableException(order.getOrderCode(), order.getOrderStatus());
        }
        if (deliveryMethod == null || deliveryMethod.isBlank()) {
            throw new ValidationException("A delivery method is required.");
        }
        DeliveryMethod requested =
                DeliveryMethod.valueOf(deliveryMethod.trim().toUpperCase(java.util.Locale.ROOT));
        // Counter Sale (walk-in shop order) never goes through a delivery partner.
        if (order.getLeadSource() == LeadSource.COUNTER_SALE && requested != DeliveryMethod.IN_HOUSE) {
            throw new ValidationException(
                    "This order is a Counter Sale and cannot be assigned a delivery partner.");
        }
        DeliveryMethod previous = order.getDeliveryMethod();
        order.setDeliveryMethod(requested);
        // Switching a QuikShipX order to in-house (e.g. recovering from a courier
        // failure like a non-serviceable pincode): detach it from QuikShipX —
        // best-effort cancel any shipment, resolve lingering QuikShipX outbox
        // events, and clear the failure reason — so it flows cleanly through the
        // in-house delivery path. Guarded on the actual QUIKSHIPX→IN_HOUSE switch.
        if (previous == DeliveryMethod.QUIKSHIPX && requested == DeliveryMethod.IN_HOUSE) {
            // Clear the stale failure reason on the order we return/save, and run
            // the QuikShipX-side detach cleanup (cancel shipment + resolve outbox).
            order.setQuikShipXFailureReason(null);
            if (quikShipXService != null) {
                quikShipXService.detachForInHouse(order.getId());
            }
        }
        OrderEntity saved = orderRepository.save(order);
        if (auditService != null && previous != requested) {
            auditService.record(AuditActions.ORDER_UPDATED, AuditActions.ENTITY_ORDER,
                    String.valueOf(saved.getId()),
                    "Changed delivery method of order " + saved.getOrderCode()
                            + ": " + previous + " \u2192 " + requested);
        }
        return OrderResponse.from(saved);
    }

    /**
     * Rejects a pending order with a mandatory reason (Req 9.4):
     * {@code Pending_Admin_Approval → Rejected} via the state machine, storing the
     * reason on the order. A blank reason is rejected with 400 and the order's
     * status is left unchanged. Rejected with 409 if the transition is not legal.
     */
    @Transactional
    public OrderResponse reject(Long id, String reason, AuthPrincipal admin) {
        return reject(id, null, reason, admin);
    }

    /**
     * Rejects a pending order with a categorized reason (rejection-status
     * feature): {@code Pending_Admin_Approval → REJECTED} via the state machine,
     * storing both the {@link RejectReason} category (Rate Issue / Address-Pincode
     * Issue / Other) and the free-text note on the order so the salesperson can
     * see why. A blank note is rejected with 400 and the order left unchanged.
     * Rejected with 409 if the transition is not legal.
     */
    @Transactional
    public OrderResponse reject(Long id, RejectReason category, String reason, AuthPrincipal admin) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("A rejection reason is required.");
        }
        OrderEntity order = requireOrder(id);
        orderWorkflowService.applyTransition(
                order, OrderStatus.REJECTED, Actor.user(admin, SOURCE_ADMIN));
        order.setRejectReason(category, reason.trim());
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * Cancels an order with a mandatory note (order-cancellation feature). Unlike
     * {@link #reject}, this works at ANY pre-delivery stage — including after a
     * QuikShipX tracking id (AWB) has been generated — for the real-world cases the
     * client needs: the payment never arrived, or the customer cancels after a
     * partial payment.
     *
     * <p>Steps (all in one transaction):
     * <ol>
     *   <li>transition {@code … → CANCELLED} via the central
     *       {@link OrderWorkflowService} (403 if the caller is not ADMIN, 409 if the
     *       order is already delivered/closed/returned — a delivered order is a
     *       Return, not a Cancel);</li>
     *   <li>store the mandatory note on the order (reusing {@code rejection_reason}
     *       as the "why it was stopped" field — the CANCELLED status distinguishes it
     *       from a rejection);</li>
     *   <li>clear the on-delivery dues: zero the COD amount and the customer
     *       outstanding so the cancelled order drops off the collectibles/dashboards
     *       (mirrors the RTO give-up handling). The amount already received is left
     *       untouched — any refund owed is a separate manual action, documented in
     *       the note;</li>
     *   <li>for a QuikShipX order, request cancellation at QuikShipX so the courier
     *       is NOT sent to pick the parcel up (best-effort — the OMS cancellation
     *       always succeeds even if the courier call fails; the outcome is audited).</li>
     * </ol>
     * The CANCELLED transition itself fans out the standard cancellation
     * notification via the workflow service.
     *
     * @return the cancelled order plus whether the courier was told to abort pickup
     */
    @Transactional
    public CancelResult cancel(Long id, String note, AuthPrincipal admin) {
        if (note == null || note.isBlank()) {
            throw new ValidationException("A cancellation note is required.");
        }
        OrderEntity order = requireOrder(id);
        String trimmedNote = note.trim();

        // 1. Transition to CANCELLED through the central workflow (authorize +
        // legality + history + audit + notification fan-out).
        orderWorkflowService.applyTransition(
                order, OrderStatus.CANCELLED, Actor.user(admin, SOURCE_ADMIN));

        // 2. Record the mandatory note (reuses the generic "why stopped" field).
        order.setRejectionReason(trimmedNote);

        // 3. Clear on-delivery dues — nothing to collect for a cancelled order
        // (mirrors the RTO give-up handling). Amount received is untouched.
        order.applyAmounts(order.getTotalAmount(), order.getAmountReceived(),
                order.getRemainingAmount(), java.math.BigDecimal.ZERO, order.getPaymentStatus());
        order.setCustomerOutstanding(java.math.BigDecimal.ZERO);

        OrderEntity saved = orderRepository.save(order);

        // 4. Tell QuikShipX to cancel the shipment (abort courier pickup). Best-effort.
        QuikShipXService.CancelOutcome courierOutcome = null;
        if (quikShipXService != null) {
            courierOutcome = quikShipXService.cancelForOrder(saved.getId());
        }

        boolean courierCancelAttempted = courierOutcome != null && courierOutcome.attempted();
        boolean courierCancelAccepted = courierOutcome != null && courierOutcome.accepted();
        String courierMessage = courierOutcome == null ? null : courierOutcome.message();

        // Audit the cancellation with the courier outcome, so the admin can see
        // whether the pickup was actually stopped at the partner.
        if (auditService != null) {
            String detail = "Cancelled order " + saved.getOrderCode() + " — " + trimmedNote;
            if (courierCancelAttempted) {
                detail += courierCancelAccepted
                        ? " [QuikShipX pickup aborted]"
                        : " [QuikShipX cancel NOT confirmed: " + courierMessage + " — follow up with the courier]";
            }
            auditService.record(AuditActions.ORDER_CANCELLED, AuditActions.ENTITY_ORDER,
                    String.valueOf(saved.getId()), detail);
        }

        return new CancelResult(OrderResponse.from(saved),
                courierCancelAttempted, courierCancelAccepted, courierMessage);
    }

    /**
     * The outcome of an admin cancellation: the cancelled order plus whether the
     * courier (QuikShipX) was told to abort the pickup and whether it confirmed.
     */
    public record CancelResult(OrderResponse order, boolean courierCancelAttempted,
                               boolean courierCancelAccepted, String courierMessage) {
    }

    // --- Internal helpers ---------------------------------------------------

    private OrderEntity requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
    }
}
