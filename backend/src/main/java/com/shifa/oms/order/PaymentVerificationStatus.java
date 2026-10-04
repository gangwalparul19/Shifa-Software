package com.shifa.oms.order;

/**
 * Authenticity-verification state of an order's customer payment (product-audit
 * §4.4). This is an additive layer alongside the order <em>state machine</em>
 * (it adds no status), but it <em>does</em> gate the admin approval action
 * (payment-verification-gated approval): an order whose payment is {@link #PENDING}
 * or {@link #REJECTED} cannot be approved until a verifier/admin confirms the
 * payment. Only prepaid / partially-paid orders (amount received &gt; 0) carry a
 * verification status; pure COD orders leave it {@code null} (nothing to verify,
 * approval not gated).
 */
public enum PaymentVerificationStatus {

    /** Payment recorded, awaiting a verifier to confirm the screenshot vs. amount. */
    PENDING,

    /** A verifier confirmed the payment is genuine. */
    VERIFIED,

    /** A verifier flagged the payment as not genuine / mismatched. */
    REJECTED
}
