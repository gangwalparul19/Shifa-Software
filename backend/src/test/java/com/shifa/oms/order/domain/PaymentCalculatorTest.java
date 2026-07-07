package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Example-based unit tests for {@link PaymentCalculator} covering concrete cases
 * and edge conditions that complement the property tests (Requirement 7.3-7.10).
 */
class PaymentCalculatorTest {

    @Test
    void totalIsExactSumOfLineTotals() {
        List<LineItem> items = List.of(
                LineItem.of(2, Money.of("10.50")),   // 21.00
                LineItem.of(3, Money.of("4.99")));    // 14.97
        assertThat(PaymentCalculator.totalAmount(items)).isEqualTo(Money.of("35.97"));
    }

    @Test
    void emptyOrderIsRejected() {
        assertThatThrownBy(() -> PaymentCalculator.totalAmount(List.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void zeroReceivedIsCodForFullTotal() {
        PaymentCalculation r = PaymentCalculator.classify(Money.of("100.00"), Money.ZERO);
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(r.codAmount()).isEqualTo(Money.of("100.00"));
        assertThat(r.remainingAmount()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void partialReceivedIsPartiallyPaidWithRemainingCod() {
        PaymentCalculation r = PaymentCalculator.classify(Money.of("100.00"), Money.of("30.00"));
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(r.remainingAmount()).isEqualTo(Money.of("70.00"));
        assertThat(r.codAmount()).isEqualTo(Money.of("70.00"));
    }

    @Test
    void fullReceivedIsFullyPaidWithZeroCod() {
        PaymentCalculation r = PaymentCalculator.classify(Money.of("100.00"), Money.of("100.00"));
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
        assertThat(r.remainingAmount()).isEqualTo(Money.ZERO);
        assertThat(r.codAmount()).isEqualTo(Money.ZERO);
    }

    @Test
    void receivedExceedingTotalIsRejected() {
        assertThatThrownBy(() ->
                PaymentCalculator.classify(Money.of("100.00"), Money.of("100.01")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void screenshotRequiredWhenMoneyReceived() {
        assertThat(PaymentCalculator.isSubmissionPermitted(Money.of("50.00"), "payments/1/x.jpg")).isTrue();
        assertThat(PaymentCalculator.isSubmissionPermitted(Money.of("50.00"), null)).isFalse();
        assertThat(PaymentCalculator.isSubmissionPermitted(Money.ZERO, null)).isTrue();
    }
}
