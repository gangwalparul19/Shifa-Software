package com.shifa.oms.order;

/**
 * The categorized reason an order was rejected (rejection-status feature).
 * Captured when an admin rejects a pending order, so the salesperson sees a
 * concrete category rather than only free text; persisted on
 * {@code orders.reject_reason} as {@code VARCHAR(30)} via
 * {@code @Enumerated(EnumType.STRING)}. Mirrors the {@link RtoReason}
 * categorized-reason pattern (a free-text note accompanies it).
 *
 * <ul>
 *   <li>{@link #RATE_ISSUE} — the order's pricing/rate was wrong or disputed.</li>
 *   <li>{@link #ADDRESS_PINCODE_ISSUE} — the delivery address / pincode was
 *       incorrect, incomplete, or not serviceable.</li>
 *   <li>{@link #PAYMENT_ISSUE} — the payment could not be verified / was not
 *       genuine. Set automatically when the order is rejected from the Payment
 *       Verification panel (the order moves to {@code PAYMENT_REJECTED}).</li>
 *   <li>{@link #OTHER} — anything else; accompanied by the free-text
 *       {@code rejection_reason} note.</li>
 * </ul>
 */
public enum RejectReason {
    RATE_ISSUE,
    ADDRESS_PINCODE_ISSUE,
    PAYMENT_ISSUE,
    OTHER
}
