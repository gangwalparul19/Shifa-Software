package com.shifa.oms.payment;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderService;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.payment.dto.ConfirmPaymentRequest;
import com.shifa.oms.payment.dto.ConfirmPaymentResponse;
import com.shifa.oms.payment.dto.InitiatePaymentResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link PaymentService}, using the real
 * {@link SandboxPaymentGateway} (so the HMAC create/verify flow is genuine) over
 * mocked repositories and a mocked {@link OrderService}. No database / network.
 *
 * <p>Covers: initiate creates a CREATED transaction and rejects unknown /
 * already-paid / already-processing orders; confirm marks the order FULLY_PAID
 * and the transaction PAID on a valid signature; a bad signature marks the
 * transaction FAILED and leaves the order untouched; and a double confirm on an
 * already-paid order is idempotent.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentTransactionRepository transactionRepository;
    @Mock
    private OrderRepository orderRepository;

    // OrderService is a concrete class and cannot be mocked on this JVM (Byte
    // Buddy inline mocks are unsupported on Java 25), so use a recording subclass.
    private final RecordingOrderService orderService = new RecordingOrderService();

    private final PaymentProperties properties = new PaymentProperties(
            "SANDBOX", "INR", true, new PaymentProperties.Sandbox("unit-test-secret"), null);
    private final SandboxPaymentGateway gateway = new SandboxPaymentGateway(properties);

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(gateway, transactionRepository, orderRepository,
                orderService, properties);
    }

    /** A hand-written {@link OrderService} test double recording markPaidOnline calls. */
    private static final class RecordingOrderService extends OrderService {
        private int markPaidCalls = 0;
        private Long lastOrderId;
        private OrderEntity result;

        private RecordingOrderService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        @Override
        public OrderEntity markPaidOnline(Long orderId) {
            this.markPaidCalls++;
            this.lastOrderId = orderId;
            return result;
        }
    }

    private OrderEntity order(long id, OrderStatus status, PaymentStatus paymentStatus, String total) {
        OrderEntity order = new OrderEntity("ORD-" + id, OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "1 Herbal St", "Pune", "Maharashtra", "411001");
        BigDecimal amount = new BigDecimal(total).setScale(2);
        BigDecimal cod = paymentStatus == PaymentStatus.FULLY_PAID ? BigDecimal.ZERO.setScale(2) : amount;
        BigDecimal received = paymentStatus == PaymentStatus.FULLY_PAID ? amount : BigDecimal.ZERO.setScale(2);
        order.applyAmounts(amount, received, amount.subtract(received), cod, paymentStatus);
        order.setOrderStatus(status);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    // --- initiate -----------------------------------------------------------

    @Test
    void initiateCreatesTransactionForPayableOrder() {
        OrderEntity order = order(1L, OrderStatus.PENDING_ADMIN_APPROVAL, PaymentStatus.COD, "500.00");
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(transactionRepository.save(any(PaymentTransaction.class))).thenAnswer(inv -> {
            PaymentTransaction t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 99L);
            return t;
        });

        InitiatePaymentResponse response = service.initiate(1L);

        assertThat(response.gateway()).isEqualTo("SANDBOX");
        assertThat(response.gatewayOrderId()).startsWith("SBX_ORD_ORD-1_");
        assertThat(response.amount()).isEqualByComparingTo("500.00");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.keyId()).isNull();
        assertThat(response.paymentTxnId()).isEqualTo(99L);
        assertThat(response.clientToken()).contains(":");

        // Persisted a CREATED transaction for the correct order/amount.
        verify(transactionRepository).save(any(PaymentTransaction.class));
    }

    @Test
    void initiateRejectsUnknownOrder() {
        when(orderRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiate(7L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void initiateRejectsAlreadyPaidOrder() {
        OrderEntity order = order(2L, OrderStatus.PENDING_ADMIN_APPROVAL, PaymentStatus.FULLY_PAID, "500.00");
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.initiate(2L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already paid");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void initiateRejectsOrderPastApprovalState() {
        OrderEntity order = order(3L, OrderStatus.APPROVED, PaymentStatus.COD, "500.00");
        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.initiate(3L))
                .isInstanceOf(ValidationException.class);
        verify(transactionRepository, never()).save(any());
    }

    // --- confirm ------------------------------------------------------------

    @Test
    void confirmMarksOrderPaidAndTransactionPaidOnValidSignature() {
        OrderEntity order = order(10L, OrderStatus.PENDING_ADMIN_APPROVAL, PaymentStatus.COD, "500.00");
        PaymentSession session = gateway.createSession(order, 50000L, "INR");
        String[] parts = session.clientToken().split(":", 2);
        String paymentId = parts[0];
        String signature = parts[1];

        PaymentTransaction txn = new PaymentTransaction(10L, "SANDBOX", session.gatewayOrderId(),
                new BigDecimal("500.00"));

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(transactionRepository.findByGatewayOrderId(session.gatewayOrderId()))
                .thenReturn(Optional.of(txn));
        orderService.result = order;

        ConfirmPaymentResponse response = service.confirm(new ConfirmPaymentRequest(
                10L, session.gatewayOrderId(), paymentId, signature));

        assertThat(response.paid()).isTrue();
        assertThat(response.orderCode()).isEqualTo("ORD-10");
        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.PAID);
        assertThat(txn.getGatewayPaymentId()).isEqualTo(paymentId);
        assertThat(orderService.markPaidCalls).isEqualTo(1);
        assertThat(orderService.lastOrderId).isEqualTo(10L);
        verify(transactionRepository).save(txn);
    }

    @Test
    void confirmMarksTransactionFailedAndLeavesOrderUnchangedOnBadSignature() {
        OrderEntity order = order(11L, OrderStatus.PENDING_ADMIN_APPROVAL, PaymentStatus.COD, "500.00");
        PaymentSession session = gateway.createSession(order, 50000L, "INR");
        String paymentId = session.clientToken().split(":", 2)[0];

        PaymentTransaction txn = new PaymentTransaction(11L, "SANDBOX", session.gatewayOrderId(),
                new BigDecimal("500.00"));

        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));
        when(transactionRepository.findByGatewayOrderId(session.gatewayOrderId()))
                .thenReturn(Optional.of(txn));

        ConfirmPaymentResponse response = service.confirm(new ConfirmPaymentRequest(
                11L, session.gatewayOrderId(), paymentId, "not-a-valid-signature"));

        assertThat(response.paid()).isFalse();
        assertThat(txn.getStatus()).isEqualTo(PaymentTransactionStatus.FAILED);
        // Order left in COD / unpaid; never marked paid.
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(orderService.markPaidCalls).isZero();
    }

    @Test
    void confirmIsIdempotentWhenOrderAlreadyPaid() {
        OrderEntity order = order(12L, OrderStatus.PENDING_ADMIN_APPROVAL, PaymentStatus.FULLY_PAID, "500.00");
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));

        ConfirmPaymentResponse response = service.confirm(new ConfirmPaymentRequest(
                12L, "SBX_ORD_ORD-12_abc", "SBX_PAY_abc", "anything"));

        assertThat(response.paid()).isTrue();
        assertThat(response.orderCode()).isEqualTo("ORD-12");
        // Idempotent: no transaction lookup, no re-application.
        verify(transactionRepository, never()).findByGatewayOrderId(any());
        assertThat(orderService.markPaidCalls).isZero();
    }
}
