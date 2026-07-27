package com.shifa.oms.payment;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.payment.dto.PaymentQueueRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Payment authenticity verification (product-audit §4.4).
 *
 * <p>An <em>additive</em> workflow that runs alongside the order lifecycle: a
 * Payment Verifier reviews prepaid / partially-paid orders (which start
 * {@code PENDING}), inspects the payment screenshot vs. the amount, and marks
 * each {@code VERIFIED} or {@code REJECTED} with an optional note. It never
 * changes the order status, so the core state machine and its guarantees are
 * untouched; the decision is recorded on the order and audited.
 */
@Service
public class PaymentVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentVerificationService.class);

    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final Clock clock;

    @Autowired
    public PaymentVerificationService(OrderRepository orderRepository, AuditService auditService,
                                      CurrentUserService currentUserService) {
        this(orderRepository, auditService, currentUserService, Clock.systemDefaultZone());
    }

    PaymentVerificationService(OrderRepository orderRepository, AuditService auditService,
                               CurrentUserService currentUserService, Clock clock) {
        this.orderRepository = orderRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.clock = clock;
    }

    /** The queue of prepaid payments awaiting verification, oldest first. */
    @Transactional(readOnly = true)
    public List<PaymentQueueRow> queue() {
        return orderRepository
                .findByPaymentVerificationStatusOrderByCreatedAtAsc(PaymentVerificationStatus.PENDING)
                .stream()
                .map(PaymentQueueRow::from)
                .toList();
    }

    /** Marks an order's payment as genuine (product-audit §4.4). */
    @Transactional
    public OrderResponse verify(Long orderId, String note) {
        return decide(orderId, PaymentVerificationStatus.VERIFIED, note,
                AuditActions.PAYMENT_VERIFIED, "Verified payment for order ");
    }

    /** Flags an order's payment as not genuine / mismatched (product-audit §4.4). */
    @Transactional
    public OrderResponse reject(Long orderId, String note) {
        return decide(orderId, PaymentVerificationStatus.REJECTED, note,
                AuditActions.PAYMENT_REJECTED, "Rejected payment for order ");
    }

    private OrderResponse decide(Long orderId, PaymentVerificationStatus decision, String note,
                                 String auditAction, String auditPrefix) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        if (order.getPaymentVerificationStatus() == null) {
            // A pure COD order has no payment to verify.
            throw new ValidationException("Order " + order.getOrderCode()
                    + " has no payment to verify.");
        }
        Long verifierId = currentUserService.requireCurrentUser().userId();
        order.recordPaymentVerification(
                decision, verifierId, LocalDateTime.now(clock), trimToNull(note));
        OrderEntity saved = orderRepository.save(order);
        auditService.record(auditAction, AuditActions.ENTITY_ORDER,
                String.valueOf(saved.getId()), auditPrefix + saved.getOrderCode());
        log.debug("Payment for order {} marked {} by user {}",
                saved.getOrderCode(), decision, verifierId);
        return OrderResponse.from(saved);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
