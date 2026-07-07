package com.shifa.oms.returns;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.returns.dto.ReturnResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Returns / refunds / RTO application service ("operations depth" Feature 1).
 *
 * <p>Owns the return lifecycle ({@link ReturnStatus}: REQUESTED &rarr; APPROVED
 * &rarr; REFUNDED, or REJECTED) as a proper record against an order — today RTO
 * is only an order lifecycle status with no return/refund tracking. A return may
 * only be created for an order in a {@link #RETURNABLE_STATUSES returnable}
 * state, and at most one active (non-terminal) return may exist per order. On
 * approval the order's line items can optionally be returned to stock via
 * {@link StockService#returnToStock(Long, int, String, Long)}.
 *
 * <p>Every mutating operation records a best-effort audit event (never throws).
 */
@Service
public class ReturnService {

    /** Order statuses from which a return may be raised. */
    static final Set<OrderStatus> RETURNABLE_STATUSES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.RTO);

    /** Non-terminal return statuses that count as an "active" return for an order. */
    private static final Set<ReturnStatus> ACTIVE_STATUSES =
            EnumSet.of(ReturnStatus.REQUESTED, ReturnStatus.APPROVED);

    private final OrderReturnRepository returnRepository;
    private final OrderRepository orderRepository;
    private final StockService stockService;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    public ReturnService(OrderReturnRepository returnRepository,
                         OrderRepository orderRepository,
                         StockService stockService,
                         AuditService auditService,
                         CurrentUserService currentUserService) {
        this.returnRepository = returnRepository;
        this.orderRepository = orderRepository;
        this.stockService = stockService;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
    }

    /**
     * Creates a new return for an order in a returnable state (DELIVERED or RTO).
     * At most one active (non-terminal) return may exist per order. New returns
     * start {@link ReturnStatus#REQUESTED}.
     *
     * @throws ResourceNotFoundException when the order does not exist
     * @throws ValidationException       when the order is not returnable or already
     *                                   has an active return
     */
    @Transactional
    public ReturnResponse create(Long orderId, String reason, String notes) {
        OrderEntity order = requireOrder(orderId);
        if (!RETURNABLE_STATUSES.contains(order.getOrderStatus())) {
            throw new ValidationException(
                    "A return can only be created for a delivered or RTO order (order "
                            + order.getOrderCode() + " is " + order.getOrderStatus() + ").");
        }
        if (returnRepository.existsByOrderIdAndStatusIn(orderId, ACTIVE_STATUSES)) {
            throw new ValidationException(
                    "Order " + order.getOrderCode() + " already has an active return.");
        }
        Long actorId = currentUserId();
        OrderReturn saved = returnRepository.save(new OrderReturn(orderId, reason, notes, actorId));
        auditService.record(AuditActions.RETURN_CREATED, AuditActions.ENTITY_RETURN,
                String.valueOf(saved.getId()),
                "Return requested for order " + order.getOrderCode() + ": " + reason);
        return ReturnResponse.from(saved);
    }

    /**
     * Approves a REQUESTED return. When {@code restock} is true, each line item of
     * the order is returned to stock (a RETURN movement) and {@code restocked} is
     * set. An optional {@code refundAmount} is stored if provided.
     *
     * @throws ValidationException when the return is not in a state that allows approval
     */
    @Transactional
    public ReturnResponse approve(Long returnId, boolean restock, BigDecimal refundAmount) {
        OrderReturn ret = requireReturn(returnId);
        requireTransition(ret, ReturnStatus.APPROVED);

        if (restock) {
            OrderEntity order = requireOrder(ret.getOrderId());
            Long actorId = currentUserId();
            for (OrderLineItem item : order.getLineItems()) {
                if (item.getProductId() != null && item.getQuantity() > 0) {
                    stockService.returnToStock(item.getProductId(), item.getQuantity(),
                            "Return " + returnId + " for order " + order.getOrderCode(), actorId);
                }
            }
            ret.setRestocked(true);
        }
        if (refundAmount != null) {
            ret.setRefundAmount(refundAmount);
        }
        ret.changeStatus(ReturnStatus.APPROVED);
        OrderReturn saved = returnRepository.save(ret);
        auditService.record(AuditActions.RETURN_APPROVED, AuditActions.ENTITY_RETURN,
                String.valueOf(returnId),
                "Return approved" + (restock ? " (restocked)" : "")
                        + (refundAmount != null ? ", refund " + refundAmount : ""));
        return ReturnResponse.from(saved);
    }

    /**
     * Rejects a REQUESTED return (terminal). Optional {@code notes} are appended.
     *
     * @throws ValidationException when the return is not in a state that allows rejection
     */
    @Transactional
    public ReturnResponse reject(Long returnId, String notes) {
        OrderReturn ret = requireReturn(returnId);
        requireTransition(ret, ReturnStatus.REJECTED);
        if (notes != null && !notes.isBlank()) {
            ret.setNotes(notes);
        }
        ret.changeStatus(ReturnStatus.REJECTED);
        OrderReturn saved = returnRepository.save(ret);
        auditService.record(AuditActions.RETURN_REJECTED, AuditActions.ENTITY_RETURN,
                String.valueOf(returnId), "Return rejected"
                        + (notes != null && !notes.isBlank() ? ": " + notes : ""));
        return ReturnResponse.from(saved);
    }

    /**
     * Marks an APPROVED return as REFUNDED, recording the refund amount and
     * stamping {@code updated_at}.
     *
     * @throws ValidationException when the return is not APPROVED
     */
    @Transactional
    public ReturnResponse markRefunded(Long returnId, BigDecimal refundAmount) {
        OrderReturn ret = requireReturn(returnId);
        requireTransition(ret, ReturnStatus.REFUNDED);
        ret.setRefundAmount(refundAmount);
        ret.changeStatus(ReturnStatus.REFUNDED);
        OrderReturn saved = returnRepository.save(ret);
        auditService.record(AuditActions.RETURN_REFUNDED, AuditActions.ENTITY_RETURN,
                String.valueOf(returnId), "Return refunded: " + refundAmount);
        return ReturnResponse.from(saved);
    }

    /** Filtered, paged return listing (newest-first by default via the pageable). */
    @Transactional(readOnly = true)
    public PageResponse<ReturnResponse> list(ReturnStatus status, String q,
                                             LocalDateTime from, LocalDateTime to,
                                             Pageable pageable) {
        Page<OrderReturn> page = returnRepository.search(status, blankToNull(q), from, to, pageable);
        return PageResponse.of(page, ReturnResponse::from);
    }

    /** All returns for a given order, newest first. */
    @Transactional(readOnly = true)
    public List<ReturnResponse> getByOrder(Long orderId) {
        return returnRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(ReturnResponse::from)
                .toList();
    }

    /** A single return by id, or a 404. */
    @Transactional(readOnly = true)
    public ReturnResponse get(Long id) {
        return ReturnResponse.from(requireReturn(id));
    }

    // --- Internal helpers ---------------------------------------------------

    private void requireTransition(OrderReturn ret, ReturnStatus target) {
        if (!ret.getStatus().canTransitionTo(target)) {
            throw new ValidationException(
                    "Cannot " + verbFor(target) + " a return that is " + ret.getStatus() + ".");
        }
    }

    private static String verbFor(ReturnStatus target) {
        return switch (target) {
            case APPROVED -> "approve";
            case REJECTED -> "reject";
            case REFUNDED -> "mark refunded";
            default -> "transition";
        };
    }

    private OrderEntity requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " does not exist."));
    }

    private OrderReturn requireReturn(Long returnId) {
        return returnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Return " + returnId + " does not exist."));
    }

    private Long currentUserId() {
        AuthPrincipal principal = currentUserService.currentUser().orElse(null);
        return principal != null ? principal.userId() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
