package com.shifa.oms.payment;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.RejectReason;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.payment.dto.PaymentQueueRow;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** {@code vouchers.source_type} for a customer receipt (matches {@code SourceType.PAYMENT}). */
    private static final String LEDGER_SOURCE_PAYMENT = "PAYMENT";

    /** The {@code status_history} source recorded for a payment-panel rejection. */
    private static final String SOURCE_PAYMENT_PANEL = "PAYMENT";

    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final OutboxEventPublisher outboxEventPublisher;
    /**
     * Staff directory (nullable): resolves each order's {@code created_by} to the
     * salesperson's display name for the verification queue. Null under the
     * test/legacy constructor — the name is then omitted.
     */
    private final UserRepository userRepository;
    /**
     * Central workflow (nullable): used to transition a rejected payment's order
     * to {@code PAYMENT_REJECTED} so the rejection is a visible order status the
     * salesperson sees (rejection-status feature). Null under the test/legacy
     * constructor — the payment decision is then recorded without a status change.
     */
    private final OrderWorkflowService orderWorkflowService;
    private final Clock clock;

    /**
     * Test / legacy constructor (no staff directory, no workflow): the salesperson
     * name on the payment queue is omitted and a rejection does not change the
     * order status. The existing 4-arg test call site keeps working unchanged.
     */
    public PaymentVerificationService(OrderRepository orderRepository, AuditService auditService,
                                      CurrentUserService currentUserService,
                                      OutboxEventPublisher outboxEventPublisher) {
        this(orderRepository, auditService, currentUserService, outboxEventPublisher,
                null, null, Clock.systemDefaultZone());
    }

    @Autowired
    public PaymentVerificationService(OrderRepository orderRepository, AuditService auditService,
                                      CurrentUserService currentUserService,
                                      OutboxEventPublisher outboxEventPublisher,
                                      UserRepository userRepository,
                                      OrderWorkflowService orderWorkflowService) {
        this(orderRepository, auditService, currentUserService, outboxEventPublisher,
                userRepository, orderWorkflowService, Clock.systemDefaultZone());
    }

    PaymentVerificationService(OrderRepository orderRepository, AuditService auditService,
                               CurrentUserService currentUserService,
                               OutboxEventPublisher outboxEventPublisher,
                               UserRepository userRepository,
                               OrderWorkflowService orderWorkflowService, Clock clock) {
        this.orderRepository = orderRepository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.userRepository = userRepository;
        this.orderWorkflowService = orderWorkflowService;
        this.clock = clock;
    }

    /** The queue of prepaid payments awaiting verification, oldest first. */
    @Transactional(readOnly = true)
    public List<PaymentQueueRow> queue() {
        List<OrderEntity> pending = orderRepository
                .findByPaymentVerificationStatusOrderByCreatedAtDesc(PaymentVerificationStatus.PENDING);
        // Resolve each row's salesperson (created_by) name once for the whole
        // queue, so the verifier sees who punched each order (no N+1).
        Map<Long, String> names = resolveSalespersonNames(pending);
        return pending.stream()
                .map(o -> PaymentQueueRow.from(
                        o, o.getCreatedBy() == null ? null : names.get(o.getCreatedBy())))
                .toList();
    }

    /**
     * Batch-resolves the display names of the salespeople who created the given
     * orders (full name, else username), mirroring the packing pattern. Empty when
     * there is no staff directory (test/legacy) or nothing to resolve.
     */
    private Map<Long, String> resolveSalespersonNames(List<OrderEntity> orders) {
        Map<Long, String> names = new HashMap<>();
        if (userRepository == null) {
            return names;
        }
        Set<Long> ids = new HashSet<>();
        for (OrderEntity o : orders) {
            if (o.getCreatedBy() != null) {
                ids.add(o.getCreatedBy());
            }
        }
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    /** Marks an order's payment as genuine (product-audit §4.4). */
    @Transactional
    public OrderResponse verify(Long orderId, String note) {
        OrderResponse response = decide(orderId, PaymentVerificationStatus.VERIFIED, note,
                AuditActions.PAYMENT_VERIFIED, "Verified payment for order ");
        // Auto-posting (Reqs 11.1, 11.3, 17.3, 17.4): a verified customer payment is the recorded
        // receipt, so enqueue a ledger-post event in this same transaction. The source id is the
        // ORDER id (LedgerAutoPostingService.buildReceiptDraft loads the order by this id and posts
        // the amount received). The event row commits atomically with the verification; a downstream
        // posting failure can never roll back or alter this order (additive — behaviour unchanged).
        outboxEventPublisher.publishLedgerPost(LEDGER_SOURCE_PAYMENT, orderId);
        return response;
    }

    /**
     * Flags an order's payment as not genuine / mismatched (product-audit §4.4)
     * AND moves the order to the visible {@code PAYMENT_REJECTED} status
     * (rejection-status feature), tagging the categorized reason
     * {@link RejectReason#PAYMENT_ISSUE} plus the verifier's note, so the
     * salesperson sees the order under "Rejected" with the reason. The lifecycle
     * transition runs only when the central workflow is available (production);
     * the payment decision is always recorded.
     */
    @Transactional
    public OrderResponse reject(Long orderId, String note) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        if (order.getPaymentVerificationStatus() == null) {
            throw new ValidationException("Order " + order.getOrderCode()
                    + " has no payment to verify.");
        }
        AuthPrincipal verifier = currentUserService.requireCurrentUser();
        String trimmedNote = trimToNull(note);
        order.recordPaymentVerification(
                PaymentVerificationStatus.REJECTED, verifier.userId(),
                LocalDateTime.now(clock), trimmedNote);
        // Make the rejection a visible order status the salesperson can see.
        // Only legal from PENDING_ADMIN_APPROVAL / APPROVED; guarded so the
        // test/legacy path (null workflow, no status) records the decision only.
        if (orderWorkflowService != null && isPaymentRejectable(order.getOrderStatus())) {
            orderWorkflowService.applyTransition(
                    order, OrderStatus.PAYMENT_REJECTED, Actor.user(verifier, SOURCE_PAYMENT_PANEL));
            order.setRejectReason(RejectReason.PAYMENT_ISSUE, trimmedNote);
        }
        OrderEntity saved = orderRepository.save(order);
        auditService.record(AuditActions.PAYMENT_REJECTED, AuditActions.ENTITY_ORDER,
                String.valueOf(saved.getId()), "Rejected payment for order " + saved.getOrderCode());
        log.debug("Payment for order {} REJECTED by user {}", saved.getOrderCode(), verifier.userId());
        return OrderResponse.from(saved);
    }

    private static boolean isPaymentRejectable(OrderStatus status) {
        return status == OrderStatus.PENDING_ADMIN_APPROVAL || status == OrderStatus.APPROVED;
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
