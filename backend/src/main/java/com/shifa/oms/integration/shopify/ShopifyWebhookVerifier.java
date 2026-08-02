package com.shifa.oms.integration.shopify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies the signature on an inbound Shopify order webhook (Req 2.2, 2.3).
 *
 * <p>Two things differ from the existing courier verifier, both deliberate:
 *
 * <ul>
 *   <li><b>Base64, not hex.</b> Shopify sends a base64-encoded HMAC-SHA256 of the raw body
 *       in {@code X-Shopify-Hmac-Sha256}. Comparing a hex digest against it would reject
 *       every genuine delivery.</li>
 *   <li><b>Fails closed on a missing secret.</b> The courier verifier accepts unsigned
 *       webhooks when no secret is configured, which is convenient in dev. Doing that here
 *       would let anyone on the internet create orders in the OMS by POSTing JSON, so a
 *       blank secret rejects every delivery instead.</li>
 * </ul>
 *
 * <p>Comparison is constant-time via {@link MessageDigest#isEqual}, so a caller cannot
 * discover the expected signature one byte at a time by timing responses.
 */
@Component
public class ShopifyWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ShopifyProperties properties;

    public ShopifyWebhookVerifier(ShopifyProperties properties) {
        this.properties = properties;
    }

    /**
     * Whether the signature over {@code rawBody} is valid.
     *
     * @param rawBody   the exact request body bytes as received; re-serialising the parsed
     *                  JSON would change whitespace and invalidate the signature
     * @param signature the {@code X-Shopify-Hmac-Sha256} value, possibly null or blank
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (!properties.hasWebhookSecret()) {
            // Fail closed: an unauthenticated order-creation endpoint is worse than a
            // broken integration.
            log.warn("Shopify webhook secret is not configured — rejecting the delivery. "
                    + "Set app.shopify.webhook-secret to enable ingestion.");
            return false;
        }
        if (rawBody == null || signature == null || signature.isBlank()) {
            return false;
        }

        byte[] expected = sign(rawBody, properties.webhookSecret()).getBytes(StandardCharsets.UTF_8);
        byte[] provided = signature.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    /**
     * Computes the base64 HMAC-SHA256 Shopify would send for a body. Used by tests and by
     * the admin replay path.
     */
    public static String sign(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute the Shopify HMAC signature", e);
        }
    }
}
