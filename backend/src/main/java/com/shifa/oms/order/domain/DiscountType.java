package com.shifa.oms.order.domain;

/**
 * The kind of order-level discount entered at order entry
 * (product-catalog-pricing-gst Req 6.1):
 *
 * <ul>
 *   <li>{@link #NONE} — no discount.</li>
 *   <li>{@link #FLAT} — a rupee amount subtracted from the order subtotal.</li>
 *   <li>{@link #PERCENT} — a percentage (0–100) of the subtotal.</li>
 * </ul>
 */
public enum DiscountType {
    NONE,
    FLAT,
    PERCENT;

    /**
     * Parses a wire value (case-insensitive) to a {@link DiscountType}, treating
     * null/blank as {@link #NONE}.
     */
    public static DiscountType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return DiscountType.valueOf(raw.trim().toUpperCase());
    }
}
