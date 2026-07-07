package com.shifa.oms.payment;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.payment.dto.ConfirmPaymentRequest;
import com.shifa.oms.payment.dto.ConfirmPaymentResponse;
import com.shifa.oms.payment.dto.InitiatePaymentResponse;
import com.shifa.oms.payment.dto.PaymentTransactionResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Online-payments application service (Phase E). Orchestrates the two-step
 * gateway flow behind the swappable {@link PaymentGateway}:
 *
 * <ol>
 *   <li>{@link #initiate(Long)} — validates the order is payable (exists, not
 *       already paid, still in the early {@code Pending_Admin_Approval} state),
 *       creates a {@link PaymentTransaction} row ({@code CREATED}) and a gateway
 *       session, and returns what the client needs to pay.</li>
 *   <li>{@link #confirm(ConfirmPaymentRequest)} — verifies the payment via the
 *       gateway; on success marks the transaction {@code PAID} and the order
 *       {@code FULLY_PAID} (via {@link OrderService#markPaidOnline(Long)}); on
 *       failure marks the transaction {@code FAILED} and leaves the order
 *       unchanged.</li>
 * </ol>
 *
 * <p><strong>Idempotency / double-pay guard</strong>: if the order is already
 * {@code FULLY_PAID}, {@code confirm} returns success without re-applying, and
 * {@code initiate} rejects (nothing left to pay). A transaction that is already
 * {@code PAID} short-circuits to success too.
 */
@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final PaymentTransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentProperties properties;

    public PaymentService(PaymentGateway gateway,
                          PaymentTransactionRepository transactionRepository,
                          OrderRepository orderRepository,
                          OrderService orderService,
                          PaymentProperties properties) {
        this.gateway = gateway;
        this.transactionRepository = transactionRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.properties = properties;
    }

    /**
     * Creates a payment session for an order. Rejects an unknown order, an order
     * that is already fully paid, or one that has moved past the initial
     * {@code Pending_Admin_Approval} state (payment is only offered right after
     * checkout). Persists a {@code CREATED} transaction and returns the gateway
     * session details.
     */
    @Transactional
    public InitiatePaymentResponse initiate(Long orderId) {
        if (!properties.isEnabled()) {
            throw new ValidationException("Online payment is not enabled.");
        }
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getPaymentStatus() == PaymentStatus.FULLY_PAID) {
            throw new ValidationException("This order is already paid.");
        }
        if (order.getOrderStatus() != OrderStatus.PENDING_ADMIN_APPROVAL) {
            throw new ValidationException(
                    "This order can no longer be paid online (it is already being processed).");
        }

        BigDecimal amount = order.getTotalAmount();
        long amountPaise = amount.movePointRight(2).longValueExact();
        String currency = properties.currency();

        PaymentSession session = gateway.createSession(order, amountPaise, currency);

        PaymentTransaction txn = new PaymentTransaction(
                order.getId(), session.gateway(), session.gatewayOrderId(), amount);
        txn = transactionRepository.save(txn);

        return new InitiatePaymentResponse(
                session.gateway(),
                session.gatewayOrderId(),
                amount,
                currency,
                session.keyId(),
                txn.getId(),
                session.clientToken());
    }

    /**
     * Verifies a payment result and, on success, marks the order paid. Idempotent
     * and safe against double submission.
     */
    @Transactional
    public ConfirmPaymentResponse confirm(ConfirmPaymentRequest request) {
        OrderEntity order = orderRepository.findById(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + request.orderId() + " does not exist."));

        // Double-pay guard: already fully paid → success without re-applying.
        if (order.getPaymentStatus() == PaymentStatus.FULLY_PAID) {
            return new ConfirmPaymentResponse(true, order.getOrderCode());
        }

        PaymentTransaction txn = transactionRepository.findByGatewayOrderId(request.gatewayOrderId())
                .orElseThrow(() -> new ValidationException(
                        "No payment session found for this order."));
        if (!txn.getOrderId().equals(order.getId())) {
            throw new ValidationException("The payment session does not belong to this order.");
        }

        // A transaction already verified → treat as success (idempotent).
        if (txn.isPaid()) {
            orderService.markPaidOnline(order.getId());
            return new ConfirmPaymentResponse(true, order.getOrderCode());
        }

        PaymentVerification verification =
                gateway.verify(request.gatewayOrderId(), request.gatewayPaymentId(), request.signature());

        if (!verification.success()) {
            txn.markFailed(request.gatewayPaymentId());
            transactionRepository.save(txn);
            return new ConfirmPaymentResponse(false, order.getOrderCode());
        }

        txn.markPaid(verification.gatewayPaymentId());
        transactionRepository.save(txn);
        OrderEntity paid = orderService.markPaidOnline(order.getId());
        return new ConfirmPaymentResponse(true, paid.getOrderCode());
    }

    /**
     * Lists an order's payment transactions, newest first (admin visibility).
     */
    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> transactionsForOrder(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Order " + orderId + " does not exist.");
        }
        return transactionRepository.findByOrderIdOrderByCreatedAtDescIdDesc(orderId).stream()
                .map(PaymentTransactionResponse::from)
                .toList();
    }
}
