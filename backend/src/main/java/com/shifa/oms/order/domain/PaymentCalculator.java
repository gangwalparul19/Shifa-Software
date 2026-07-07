package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;

import java.util.List;
import java.util.Objects;

/**
 * Pure pricing and payment-classification logic for an Order (Requirement 7).
 *
 * <p>This is the financially critical core of the platform and is intentionally
 * free of persistence and Spring so it can be exercised exhaustively by
 * property-based tests and reused by the order aggregate (task 9), the state
 * machine (task 4), and settlement (task 5).
 *
 * <p>All arithmetic goes through {@link Money} (BigDecimal, scale 2, HALF_UP);
 * no floating point is used.
 */
public final class PaymentCalculator {

    private PaymentCalculator() {
    }

    /**
     * Computes {@code Total_Amount = Σ (rate × quantity)} over all line items
     * (Requirement 7.4).
     *
     * @param lineItems the order line items; must be non-null and non-empty
     * @return the exact total amount
     */
    public static Money totalAmount(List<LineItem> lineItems) {
        Objects.requireNonNull(lineItems, "lineItems");
        if (lineItems.isEmpty()) {
            throw new ValidationException("An order must contain at least one line item");
        }
        Money total = Money.ZERO;
        for (LineItem item : lineItems) {
            total = total.add(item.lineTotal());
        }
        return total;
    }

    /**
     * Computes the full payment classification for a set of line items and an
     * amount received (Requirement 7.4, 7.5, 7.7, 7.8, 7.9, 7.10).
     *
     * @param lineItems      the order line items
     * @param amountReceived the amount already paid at entry
     * @return the derived payment calculation
     * @throws ValidationException if {@code amountReceived > totalAmount} (Req 7.10)
     */
    public static PaymentCalculation calculate(List<LineItem> lineItems, Money amountReceived) {
        Money total = totalAmount(lineItems);
        return classify(total, amountReceived);
    }

    /**
     * Derives {@code Remaining_Amount}, {@code Payment_Status} and
     * {@code COD_Amount} from a total and an amount received
     * (Requirement 7.5, 7.7, 7.8, 7.9, 7.10).
     *
     * <ul>
     *   <li>{@code received = 0} → {@link PaymentStatus#COD}, COD_Amount = total (Req 7.7)</li>
     *   <li>{@code 0 < received < total} → {@link PaymentStatus#PARTIALLY_PAID}, COD_Amount = remaining (Req 7.8)</li>
     *   <li>{@code received = total} → {@link PaymentStatus#FULLY_PAID}, COD_Amount = 0 (Req 7.9)</li>
     *   <li>{@code received > total} → rejected (Req 7.10)</li>
     * </ul>
     */
    public static PaymentCalculation classify(Money totalAmount, Money amountReceived) {
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(amountReceived, "amountReceived");
        if (totalAmount.isNegative()) {
            throw new ValidationException("Total_Amount must not be negative");
        }
        if (amountReceived.isNegative()) {
            throw new ValidationException("Amount_Received must not be negative");
        }
        if (amountReceived.compareTo(totalAmount) > 0) {
            throw new ValidationException("Amount_Received exceeds Total_Amount");
        }

        Money remaining = totalAmount.subtract(amountReceived);

        PaymentStatus status;
        Money codAmount;
        if (amountReceived.isZero()) {
            status = PaymentStatus.COD;
            codAmount = totalAmount;
        } else if (amountReceived.compareTo(totalAmount) < 0) {
            status = PaymentStatus.PARTIALLY_PAID;
            codAmount = remaining;
        } else {
            status = PaymentStatus.FULLY_PAID;
            codAmount = Money.ZERO;
        }

        return new PaymentCalculation(totalAmount, amountReceived, remaining, codAmount, status);
    }

    /**
     * The screenshot-required rule for order-entry submission (Requirement 7.6):
     * a submission is permitted only if {@code Amount_Received = 0} or a payment
     * screenshot key is present.
     *
     * @param amountReceived      the amount received at entry
     * @param paymentScreenshotKey the Object Storage key of the attached screenshot, or {@code null}/blank if none
     * @return {@code true} if the submission may proceed
     */
    public static boolean isSubmissionPermitted(Money amountReceived, String paymentScreenshotKey) {
        Objects.requireNonNull(amountReceived, "amountReceived");
        return amountReceived.isZero() || hasScreenshot(paymentScreenshotKey);
    }

    /**
     * Enforces the screenshot-required rule (Requirement 7.6): rejects the
     * submission with a {@link ValidationException} when {@code Amount_Received > 0}
     * and no payment screenshot is attached.
     */
    public static void requireScreenshotWhenPaid(Money amountReceived, String paymentScreenshotKey) {
        if (!isSubmissionPermitted(amountReceived, paymentScreenshotKey)) {
            throw new ValidationException(
                    "A Payment_Screenshot is required when Amount_Received is greater than 0");
        }
    }

    private static boolean hasScreenshot(String paymentScreenshotKey) {
        return paymentScreenshotKey != null && !paymentScreenshotKey.isBlank();
    }
}
