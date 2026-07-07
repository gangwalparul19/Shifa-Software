package com.shifa.oms.payment;

import com.shifa.oms.order.OrderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Razorpay gateway <strong>STUB</strong> ({@code app.payment.mode=RAZORPAY}),
 * behind the same {@link PaymentGateway} interface as the sandbox. It documents
 * exactly where a real integration plugs in without pulling in the Razorpay SDK.
 *
 * <h2>What to do for a real Razorpay integration</h2>
 * <ol>
 *   <li>Add the Razorpay Java SDK dependency (or call the REST API directly).</li>
 *   <li>In {@link #createSession}: call the Razorpay <em>Orders</em> API
 *       ({@code POST /v1/orders} with {@code amount}=paise, {@code currency},
 *       {@code receipt}=order code) using the configured {@code key-id}/
 *       {@code key-secret}. Return the Razorpay {@code order.id} as the
 *       {@code gatewayOrderId} and the <em>public</em> {@code key-id} as
 *       {@link PaymentSession#keyId()} so the browser Checkout widget can open.
 *       Leave {@code clientToken} null — the widget produces the payment id +
 *       signature itself.</li>
 *   <li>In {@link #verify}: Razorpay's client callback returns
 *       {@code razorpay_order_id}, {@code razorpay_payment_id} and
 *       {@code razorpay_signature}. Verify with
 *       {@code HmacSHA256(order_id + "|" + payment_id, key_secret)} — which is
 *       precisely {@link HmacSigner#sign} with the key-secret — and compare in
 *       constant time. That check is implemented below and is ready to use.</li>
 * </ol>
 *
 * <p>The {@code key-secret} is a server-only credential and must never be sent
 * to the browser. Only the {@code key-id} is public.
 */
@Component
@ConditionalOnProperty(name = "app.payment.mode", havingValue = "RAZORPAY")
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    private final String keyId;
    private final String keySecret;

    public RazorpayPaymentGateway(PaymentProperties properties) {
        this.keyId = properties.razorpay().keyId();
        this.keySecret = properties.razorpay().keySecret();
        log.info("RazorpayPaymentGateway active (STUB). keyId configured: {}",
                keyId != null && !keyId.isBlank());
    }

    @Override
    public String gatewayName() {
        return "RAZORPAY";
    }

    @Override
    public PaymentSession createSession(OrderEntity order, long amountPaise, String currency) {
        // TODO(real-gateway): call the Razorpay Orders API and return the real
        // order id + public key-id. Until then, fail loudly rather than pretend
        // a session exists (the SANDBOX mode is the working default).
        //
        // Example (pseudo-code) with the Razorpay SDK:
        //   RazorpayClient client = new RazorpayClient(keyId, keySecret);
        //   JSONObject req = new JSONObject()
        //       .put("amount", amountPaise)
        //       .put("currency", currency)
        //       .put("receipt", order.getOrderCode());
        //   Order rzpOrder = client.orders.create(req);
        //   return new PaymentSession(gatewayName(), rzpOrder.get("id"),
        //           amountPaise, currency, keyId, null);
        throw new UnsupportedOperationException(
                "Razorpay live integration is not implemented yet. "
                        + "Set app.payment.mode=SANDBOX for the working test flow, "
                        + "or implement the Razorpay Orders API call here.");
    }

    @Override
    public PaymentVerification verify(String gatewayOrderId, String gatewayPaymentId, String signature) {
        // This IS the real Razorpay verification: HmacSHA256(order_id|payment_id, key_secret).
        if (keySecret == null || keySecret.isBlank()) {
            return PaymentVerification.failure(gatewayPaymentId,
                    "Razorpay key-secret is not configured.");
        }
        String expected = HmacSigner.sign(keySecret, gatewayOrderId, gatewayPaymentId);
        if (HmacSigner.matches(expected, signature)) {
            return PaymentVerification.ok(gatewayPaymentId);
        }
        return PaymentVerification.failure(gatewayPaymentId, "Razorpay signature mismatch.");
    }
}
