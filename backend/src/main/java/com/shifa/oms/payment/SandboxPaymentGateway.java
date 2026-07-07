package com.shifa.oms.payment;

import com.shifa.oms.order.OrderEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The DEFAULT payment gateway ({@code app.payment.mode=SANDBOX}, also active when
 * the property is missing). It simulates a hosted-checkout provider end-to-end
 * with <strong>no real money and no network</strong>, so the whole Pay-Online
 * flow works in local dev and tests.
 *
 * <p><strong>How it stays server-authoritative</strong>: the sandbox signs test
 * payments with a server-only secret and verifies them the same way a real
 * gateway does — {@code HmacSHA256(gatewayOrderId|gatewayPaymentId, secret)}.
 * At {@code createSession} it mints a deterministic test payment id and its
 * signature and bundles them into an opaque {@link PaymentSession#clientToken()}
 * ({@code paymentId:signature}). The test client echoes those two values back at
 * confirm; the server re-verifies. The secret never leaves the server, so the
 * client cannot forge a payment — it can only replay what the sandbox already
 * signed.
 *
 * <p><strong>Seam for a real gateway</strong>: replace this bean with
 * {@link RazorpayPaymentGateway} (set {@code mode=RAZORPAY}). The real provider's
 * checkout widget supplies the payment id + signature directly (so
 * {@code clientToken} is {@code null} and {@code keyId} is the public key), and
 * the identical {@link #verify} HMAC check validates it. No server contract
 * change is required.
 */
@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "SANDBOX", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    /** Separator between the payment id and signature inside the client token. */
    static final String TOKEN_SEPARATOR = ":";

    private final String secret;

    public SandboxPaymentGateway(PaymentProperties properties) {
        this.secret = properties.sandbox().secret();
    }

    @Override
    public String gatewayName() {
        return "SANDBOX";
    }

    @Override
    public PaymentSession createSession(OrderEntity order, long amountPaise, String currency) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String gatewayOrderId = "SBX_ORD_" + order.getOrderCode() + "_" + unique;
        // The sandbox pre-generates the "payment" the test client will report,
        // and signs it server-side so verification at confirm is authentic.
        String gatewayPaymentId = "SBX_PAY_" + unique;
        String signature = HmacSigner.sign(secret, gatewayOrderId, gatewayPaymentId);
        String clientToken = gatewayPaymentId + TOKEN_SEPARATOR + signature;
        // keyId is null for the sandbox: no provider key exists and none is leaked.
        return new PaymentSession(gatewayName(), gatewayOrderId, amountPaise, currency, null, clientToken);
    }

    @Override
    public PaymentVerification verify(String gatewayOrderId, String gatewayPaymentId, String signature) {
        String expected = HmacSigner.sign(secret, gatewayOrderId, gatewayPaymentId);
        if (HmacSigner.matches(expected, signature)) {
            return PaymentVerification.ok(gatewayPaymentId);
        }
        return PaymentVerification.failure(gatewayPaymentId, "Sandbox signature mismatch.");
    }
}
