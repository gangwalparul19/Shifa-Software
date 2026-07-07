package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for payment classification and COD math.
 *
 * Feature: shifa-herbal-remedies, Property 2: Payment classification and COD
 * math. For any Order with a Total_Amount and an Amount_Received, the payment
 * computation satisfies: Remaining_Amount = Total_Amount - Amount_Received; if
 * Amount_Received = 0 then Payment_Status = COD and COD_Amount = Total_Amount;
 * if 0 < Amount_Received < Total_Amount then Payment_Status = Partially_Paid and
 * COD_Amount = Remaining_Amount; if Amount_Received = Total_Amount then
 * Payment_Status = Fully_Paid and COD_Amount = 0; and if Amount_Received >
 * Total_Amount the entry is rejected.
 *
 * Validates: Requirements 7.5, 7.7, 7.8, 7.9, 7.10
 */
class PaymentClassificationPropertyTest {

    /** Totals in paise, at least 1 paise so partial payments are representable. */
    @Provide
    Arbitrary<Money> totals() {
        return Arbitraries.longs().between(1, 10_000_000).map(Money::ofCents);
    }

    // Feature: shifa-herbal-remedies, Property 2: Payment classification and COD math
    @Property(tries = 500)
    void classificationAndCodMathHold(@ForAll("totals") Money total) {
        long totalPaise = total.toBigDecimal().movePointRight(2).longValueExact();

        // Exercise the full received range [0, total] plus the rejection region (> total).
        for (long receivedPaise : new long[] {
                0,
                totalPaise / 2,
                Math.max(0, totalPaise - 1),
                totalPaise,
                totalPaise + 1,
                totalPaise + 500 }) {

            Money received = Money.ofCents(receivedPaise);

            if (receivedPaise > totalPaise) {
                // Req 7.10: reject Amount_Received > Total_Amount.
                assertThatThrownBy(() -> PaymentCalculator.classify(total, received))
                        .isInstanceOf(ValidationException.class);
                continue;
            }

            PaymentCalculation result = PaymentCalculator.classify(total, received);

            // Remaining_Amount = Total_Amount - Amount_Received (Req 7.5).
            assertThat(result.remainingAmount())
                    .isEqualTo(total.subtract(received));

            if (receivedPaise == 0) {
                // Req 7.7
                assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.COD);
                assertThat(result.codAmount()).isEqualTo(total);
            } else if (receivedPaise < totalPaise) {
                // Req 7.8
                assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
                assertThat(result.codAmount()).isEqualTo(result.remainingAmount());
            } else {
                // Req 7.9 (received == total)
                assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
                assertThat(result.codAmount()).isEqualTo(Money.ZERO);
            }
        }
    }

    // Feature: shifa-herbal-remedies, Property 2: Payment classification and COD math
    @Property(tries = 300)
    void codAmountPlusReceivedNeverExceedsTotalAndReconstructsIt(
            @ForAll("totals") Money total,
            @ForAll long receivedRaw) {

        long totalPaise = total.toBigDecimal().movePointRight(2).longValueExact();
        // Constrain received into the valid range [0, total].
        long receivedPaise = Math.floorMod(receivedRaw, totalPaise + 1);
        Money received = Money.ofCents(receivedPaise);

        PaymentCalculation result = PaymentCalculator.classify(total, received);

        // received + remaining always reconstitutes the total exactly.
        assertThat(received.add(result.remainingAmount())).isEqualTo(total);
        // COD_Amount is never negative and never exceeds the total.
        assertThat(result.codAmount().isNegative()).isFalse();
        assertThat(result.codAmount().compareTo(total)).isLessThanOrEqualTo(0);
    }
}
