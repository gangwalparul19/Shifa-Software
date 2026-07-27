package com.shifa.oms.payment;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.payment.dto.PaymentQueueRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentVerificationService} (product-audit §4.4) with the
 * order repository mocked and a recording {@link CurrentUserService} (Java 25 —
 * no Mockito mocks of concrete classes). Verify/reject record the decision on
 * the order without touching its lifecycle status.
 */
@ExtendWith(MockitoExtension.class)
class PaymentVerificationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private PaymentVerificationService service;

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(
                mock(AuditEventRepository.class), new CurrentUserService());
        CurrentUserService currentUser = new CurrentUserService() {
            @Override
            public AuthPrincipal requireCurrentUser() {
                return new AuthPrincipal(7L, "verifier", Role.PAYMENT_VERIFIER);
            }
        };
        service = new PaymentVerificationService(orderRepository, auditService, currentUser);
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderEntity prepaidPendingOrder() {
        OrderEntity order = new OrderEntity(
                "SHR-9001", OrderSource.SALESPERSON, 3L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("2680.00"), new BigDecimal("2680.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, PaymentStatus.FULLY_PAID);
        order.setPaymentScreenshotKey("payments/1/proof.jpg");
        order.markPaymentPendingVerification();
        return order;
    }

    @Test
    void queueReturnsPendingPaymentsAsRows() {
        when(orderRepository.findByPaymentVerificationStatusOrderByCreatedAtAsc(
                PaymentVerificationStatus.PENDING))
                .thenReturn(List.of(prepaidPendingOrder()));

        List<PaymentQueueRow> rows = service.queue();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).orderCode()).isEqualTo("SHR-9001");
        assertThat(rows.get(0).paymentScreenshotAvailable()).isTrue();
        assertThat(rows.get(0).verificationStatus()).isEqualTo(PaymentVerificationStatus.PENDING);
    }

    @Test
    void verifyMarksPaymentVerifiedWithVerifierAndNote() {
        OrderEntity order = prepaidPendingOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = service.verify(1L, "Matches UPI ref 12345");

        assertThat(order.getPaymentVerificationStatus()).isEqualTo(PaymentVerificationStatus.VERIFIED);
        assertThat(order.getPaymentVerifiedBy()).isEqualTo(7L);
        assertThat(order.getPaymentVerifiedAt()).isNotNull();
        assertThat(order.getPaymentVerificationNote()).isEqualTo("Matches UPI ref 12345");
        assertThat(response.paymentVerificationStatus()).isEqualTo(PaymentVerificationStatus.VERIFIED);
        // The order status is NOT changed by verification (additive layer).
        assertThat(order.getOrderStatus()).isNull();
    }

    @Test
    void rejectMarksPaymentRejected() {
        OrderEntity order = prepaidPendingOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        service.reject(1L, "Screenshot amount does not match");

        assertThat(order.getPaymentVerificationStatus()).isEqualTo(PaymentVerificationStatus.REJECTED);
        assertThat(order.getPaymentVerificationNote()).isEqualTo("Screenshot amount does not match");
    }

    @Test
    void verifyOnCodOrderWithoutPaymentIsRejected() {
        OrderEntity cod = new OrderEntity(
                "SHR-9002", OrderSource.SALESPERSON, 3L,
                "Ravi", "9800000000", "5 Park St", "Pune", "Maharashtra", "411002");
        cod.applyAmounts(new BigDecimal("500.00"), BigDecimal.ZERO,
                new BigDecimal("500.00"), new BigDecimal("500.00"), PaymentStatus.COD);
        // No markPaymentPendingVerification() → nothing to verify.
        when(orderRepository.findById(2L)).thenReturn(Optional.of(cod));

        assertThatThrownBy(() -> service.verify(2L, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no payment to verify");
    }
}
