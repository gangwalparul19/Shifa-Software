package com.shifa.oms.shopify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies the HMAC-SHA256 signature on inbound Shopify webhooks.
 *
 * <p>Shopify signs the <em>raw</em> request body with the app's shared secret
 * ({@code app.shopify.webhook-hmac-secret}) and sends the <em>base64</em>-encoded
 * digest in the {@code X-Shopify-Hmac-Sha256} header. This verifier recomputes the
 * digest over exactly the received bytes and compares in constant time. (This
 * differs from {@link com.shifa.oms.courier.HmacSignatureVerifier}, which uses a
 * hex digest — Shopify mandates base64.)
 *
 * <p>When no secret is configured (local dev default is blank), verification is
 * skipped so the endpoint is usable without a Shopify account; the skip is logged
 * as a warning. Production MUST configure the secret.
 */
@Component
public class ShopifyHmacVerifier {

    private static final Logger log = LoggerFactory.getLogger(ShopifyHmacVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String secret;

    public ShopifyHmacVerifier(ShopifyProperties properties) {
        this.secret = properties.webhookHmacSecret();
    }

    /**
     * Whether the base64 signature over {@code rawBody} is valid.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the {@code X-Shopify-Hmac-Sha256} header value (base64; may be {@code null})
     * @return {@code true} if valid, or if no secret is configured (dev mode)
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (secret == null || secret.isBlank()) {
            log.warn("Shopify webhook HMAC secret is not configured — accepting webhook without verification.");
            return true;
        }
        if (signature == null || signature.isBlank() || rawBody == null) {
            return false;
        }
        byte[] expected = hmac(rawBody, secret);
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Constant-time comparison to avoid timing side channels.
        return MessageDigest.isEqual(expected, provided);
    }

    /** Computes the base64 HMAC-SHA256 of a payload with the given secret (test/demo helper). */
    public static String sign(byte[] rawBody, String secret) {
        return Base64.getEncoder().encodeToString(hmac(rawBody, secret));
    }

    private static byte[] hmac(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }
}
