package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;

import java.util.Objects;

/**
 * A single product line within an Order: a product with an applied rate and an
 * integer quantity (Requirement 7.2, 7.3; glossary "Line_Item").
 *
 * <p>The applied {@code rate} is editable per line (Req 7.3) and pre-filled from
 * the product default sale price by callers. The line total is {@code rate ×
 * quantity} computed with exact decimal arithmetic.
 *
 * <p>Quantity is constrained to {@code 1..999} (design: line_items quantity
 * {@code 1..999}); a non-positive or oversized quantity, or a negative rate, is
 * rejected at construction with a {@link ValidationException}.
 */
public record LineItem(String productName, int quantity, Money rate) {

    /** Minimum allowed line quantity. */
    public static final int MIN_QUANTITY = 1;

    /** Maximum allowed line quantity. */
    public static final int MAX_QUANTITY = 999;

    public LineItem {
        Objects.requireNonNull(rate, "rate");
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new ValidationException(
                    "Line item quantity must be between " + MIN_QUANTITY + " and " + MAX_QUANTITY);
        }
        if (rate.isNegative()) {
            throw new ValidationException("Line item rate must not be negative");
        }
    }

    /** Convenience factory without a product name (pure pricing use). */
    public static LineItem of(int quantity, Money rate) {
        return new LineItem(null, quantity, rate);
    }

    /** The exact line total: {@code rate × quantity}. */
    public Money lineTotal() {
        return rate.multiply(quantity);
    }
}
