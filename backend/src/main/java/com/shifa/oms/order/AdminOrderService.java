package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;
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

    private final OrderRepository orderRepository;
    private final LabelService labelService;
    private final OrderWorkflowService orderWorkflowService;

    public AdminOrderService(OrderRepository orderRepository, LabelService labelService,
                             OrderWorkflowService orderWorkflowService) {
        this.orderRepository = orderRepository;
        this.labelService = labelService;
        this.orderWorkflowService = orderWorkflowService;
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
        Specification<OrderEntity> spec =
                OrderListSpecifications.build(q, status, paymentStatus, from, to);
        return orderRepository.findAll(spec, pageable).map(OrderSummaryResponse::from);
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
        return OrderResponse.from(orderRepository.save(order));
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
