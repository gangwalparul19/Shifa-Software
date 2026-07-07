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
 * Property-based tests for the mandatory payment screenshot rule.
 *
 * Feature: shifa-herbal-remedies, Property 3: Payment screenshot mandatory when
 * money received. For any Order-entry submission, submission is permitted only
 * if Amount_Received = 0 or a Payment_Screenshot is attached; whenever
 * Amount_Received > 0 and no screenshot is attached, the submission is rejected
 * and no Order is created.
 *
 * Validates: Requirements 7.6
 */
class PaymentScreenshotPropertyTest {

    /** Amount received in paise, from 0 upward. */
    @Provide
    Arbitrary<Money> amounts() {
        return Arbitraries.longs().between(0, 10_000_000).map(Money::ofCents);
    }

    /** Either a present screenshot key or an "absent" one (null or blank). */
    @Provide
    Arbitrary<String> screenshotKeys() {
        Arbitrary<String> present = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(40)
                .map(s -> "payments/1/" + s + ".jpg");
        Arbitrary<String> absent = Arbitraries.of(null, "", "   ", "\t");
        return Arbitraries.oneOf(present, absent);
    }

    // Feature: shifa-herbal-remedies, Property 3: Payment screenshot mandatory when money received
    @Property(tries = 500)
    void submissionPermittedIffNoMoneyOrScreenshotPresent(
            @ForAll("amounts") Money amountReceived,
            @ForAll("screenshotKeys") String screenshotKey) {

        boolean hasScreenshot = screenshotKey != null && !screenshotKey.isBlank();
        boolean expectedPermitted = amountReceived.isZero() || hasScreenshot;

        boolean permitted = PaymentCalculator.isSubmissionPermitted(amountReceived, screenshotKey);
        assertThat(permitted).isEqualTo(expectedPermitted);

        if (expectedPermitted) {
            // Enforcement does not reject a permitted submission.
            PaymentCalculator.requireScreenshotWhenPaid(amountReceived, screenshotKey);
        } else {
            // Money received without a screenshot -> rejected, no order created.
            assertThatThrownBy(() ->
                    PaymentCalculator.requireScreenshotWhenPaid(amountReceived, screenshotKey))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // Feature: shifa-herbal-remedies, Property 3: Payment screenshot mandatory when money received
    @Property(tries = 300)
    void moneyReceivedWithoutScreenshotIsAlwaysRejected(
            @ForAll("amounts") Money amountReceived) {

        // Given money received (> 0) and a definitively absent screenshot.
        Money paid = amountReceived.isZero() ? Money.ofCents(1) : amountReceived;

        assertThat(PaymentCalculator.isSubmissionPermitted(paid, null)).isFalse();
        assertThatThrownBy(() -> PaymentCalculator.requireScreenshotWhenPaid(paid, null))
                .isInstanceOf(ValidationException.class);
    }
}
