package com.shifa.oms.payment;

import com.shifa.oms.order.OrderEntity;

/**
 * The swappable payment-gateway boundary (design: "Integrations are mocked
 * behind swappable interfaces"). The rest of the platform depends only on this
 * interface; the concrete provider is chosen by {@code app.payment.mode}
 * ({@link SandboxPaymentGateway} by default, {@link RazorpayPaymentGateway} for
 * a real integration).
 *
 * <p>The two-step contract mirrors every hosted-checkout provider:
 * <ol>
 *   <li>{@link #createSession(OrderEntity, long, String)} — create an order/
 *       session at the gateway and return what the client needs to pay.</li>
 *   <li>{@link #verify(String, String, String)} — verify the signed payment
 *       result server-side before the order is marked paid.</li>
 * </ol>
 */
public interface PaymentGateway {

    /** The provider name persisted on the transaction (e.g. {@code SANDBOX}). */
    String gatewayName();

    /**
     * Creates a payment session for an order at the gateway.
     *
     * @param order       the order being paid for (provides the code / metadata)
     * @param amountPaise the amount to collect in the smallest currency unit
     * @param currency    the ISO currency code (e.g. {@code INR})
     * @return the session details for the client to complete payment
     */
    PaymentSession createSession(OrderEntity order, long amountPaise, String currency);

    /**
     * Verifies a payment result returned by the client after checkout.
     *
     * @param gatewayOrderId   the gateway order id from {@link #createSession}
     * @param gatewayPaymentId the payment id reported by the gateway/client
     * @param signature        the signature to validate over order id + payment id
     * @return the verification outcome (success/failure) — never throws for a
     *         mismatched signature; that is a normal {@code success=false} result
     */
    PaymentVerification verify(String gatewayOrderId, String gatewayPaymentId, String signature);
}
