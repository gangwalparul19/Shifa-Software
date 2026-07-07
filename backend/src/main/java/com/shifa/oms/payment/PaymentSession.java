package com.shifa.oms.payment;

/**
 * Provider-agnostic result of creating a payment session at a gateway (the
 * {@code initiate} step). Modelled to fit any gateway (Razorpay/PayU/Cashfree):
 * the caller stores the {@link #gatewayOrderId()} and hands the client whatever
 * it needs to complete payment.
 *
 * @param gateway       the provider name (e.g. {@code SANDBOX}, {@code RAZORPAY})
 * @param gatewayOrderId the gateway's order/session id (echoed back at confirm)
 * @param amountPaise   the amount in the smallest currency unit (paise for INR)
 * @param currency      the ISO currency code (e.g. {@code INR})
 * @param keyId         the public key id a real client widget needs, or
 *                      {@code null} for the sandbox (no key needed / none leaked)
 * @param clientToken   an opaque token the client echoes back at confirm.
 *                      <p><strong>Sandbox only</strong>: carries a server-signed
 *                      {@code paymentId:signature} pair so the test client can
 *                      complete the flow without knowing the secret. For a real
 *                      gateway this is {@code null} — the provider's checkout
 *                      widget supplies the payment id + signature directly.
 */
public record PaymentSession(
        String gateway,
        String gatewayOrderId,
        long amountPaise,
        String currency,
        String keyId,
        String clientToken) {
}
