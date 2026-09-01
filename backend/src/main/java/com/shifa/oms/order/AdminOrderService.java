package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.quikshipx.QuikShipXProperties;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

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

    /** Legacy constructor (unit tests): no QuikShipX approval hook / list enrichment. */
    public AdminOrderService(OrderRepository orderRepository, LabelService labelService,
                             OrderWorkflowService orderWorkflowService,
                             OutboxEventPublisher outboxEventPublisher) {
        this(orderRepository, labelService, orderWorkflowService, outboxEventPublisher, null, null);
    }

    @Autowired
    public AdminOrderService(OrderRepository orderRepository, LabelService labelService,
                             OrderWorkflowService orderWorkflowService,
                             OutboxEventPublisher outboxEventPublisher,
                             QuikShipXProperties quikShipXProperties,
                             OrderShipmentRepository orderShipmentRepository) {
        this.orderRepository = orderRepository;
        this.labelService = labelService;
        this.orderWorkflowService = orderWorkflowService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.quikShipXProperties = quikShipXProperties;
        this.orderShipmentRepository = orderShipmentRepository;
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
        Specification<OrderEntity> spec =
                OrderListSpecifications.build(q, status, statusGroup, paymentStatus, from, to, creatorIds);
        Page<OrderSummaryResponse> page = orderRepository.findAll(spec, pageable).map(OrderSummaryResponse::from);
        return enrichWithQuikShipStatus(page);
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
        return orderRepository
                .findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PENDING_ADMIN_APPROVAL)
                .stream()
                .map(ApprovalQueueItemResponse::from)
                .toList();
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
        OrderEntity order = requireOrder(id);
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
        // same transaction when the integration is enabled (no-op otherwise).
        if (quikShipXProperties != null && quikShipXProperties.isEnabled()) {
            outboxEventPublisher.publishQuikShipXConfirm(saved.getId(), saved.getOrderCode());
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
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("A rejection reason is required.");
        }
        OrderEntity order = requireOrder(id);
        orderWorkflowService.applyTransition(
                order, OrderStatus.REJECTED, Actor.user(admin, SOURCE_ADMIN));
        order.setRejectionReason(reason.trim());
        return OrderResponse.from(orderRepository.save(order));
    }

    // --- Internal helpers ---------------------------------------------------

    private OrderEntity requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
    }
}
