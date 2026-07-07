package com.shifa.oms.payment;

/**
 * Provider-agnostic result of verifying a payment at the gateway (the
 * {@code confirm} step). Verification is always a <strong>server-side</strong>
 * concern: the gateway recomputes/validates the signature over the order id +
 * payment id and reports whether the payment is authentic.
 *
 * @param success          whether the payment signature verified successfully
 * @param gatewayPaymentId the gateway's payment id that was verified (echoed
 *                         back for persistence), may be {@code null} on failure
 * @param message          a short human-readable outcome (for logs / diagnostics)
 */
public record PaymentVerification(boolean success, String gatewayPaymentId, String message) {

    /** A successful verification for the given gateway payment id. */
    public static PaymentVerification ok(String gatewayPaymentId) {
        return new PaymentVerification(true, gatewayPaymentId, "Payment verified.");
    }

    /** A failed verification with a diagnostic message. */
    public static PaymentVerification failure(String gatewayPaymentId, String message) {
        return new PaymentVerification(false, gatewayPaymentId, message);
    }
}
