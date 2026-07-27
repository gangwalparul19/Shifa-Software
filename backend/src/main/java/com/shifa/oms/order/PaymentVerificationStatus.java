package com.shifa.oms.order;

/**
 * Authenticity-verification state of an order's customer payment (product-audit
 * §4.4). This is an <em>additive</em> layer alongside the order lifecycle — it
 * does not gate the order status machine. Only prepaid / partially-paid orders
 * (amount received &gt; 0) carry a verification status; pure COD orders leave it
 * {@code null} (nothing to verify).
 */
public enum PaymentVerificationStatus {

    /** Payment recorded, awaiting a verifier to confirm the screenshot vs. amount. */
    PENDING,

    /** A verifier confirmed the payment is genuine. */
    VERIFIED,

    /** A verifier flagged the payment as not genuine / mismatched. */
    REJECTED
}
