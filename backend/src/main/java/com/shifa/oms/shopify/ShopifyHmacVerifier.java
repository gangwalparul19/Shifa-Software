package com.shifa.oms.shopify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * skipped only in non-strict mode so the endpoint is usable without a Shopify
 * account. Strict mode rejects unsigned requests; production also fails startup
 * when the secret is missing.</p>
 */
@Component
public class ShopifyHmacVerifier {

    private static final Logger log = LoggerFactory.getLogger(ShopifyHmacVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String secret;
    private final boolean strict;

    /** Spring constructor. Strict mode is enabled in production and opt-in elsewhere. */
    @Autowired
    public ShopifyHmacVerifier(ShopifyProperties properties,
                               @Value("${app.webhooks.hmac-strict:false}") boolean strict) {
        this.secret = properties.webhookHmacSecret();
        this.strict = strict;
    }

    /** Explicit non-strict constructor retained for local/demo/test callers. */
    public ShopifyHmacVerifier(ShopifyProperties properties) {
        this(properties, false);
    }

    /**
     * Whether the base64 signature over {@code rawBody} is valid.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the {@code X-Shopify-Hmac-Sha256} header value (base64; may be {@code null})
     * @return {@code true} if valid, or if no secret is configured in explicit
     *         non-strict mode (local/demo/test)
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (secret == null || secret.isBlank()) {
            if (strict) {
                log.warn("Shopify webhook HMAC secret is not configured — rejecting the webhook in strict mode.");
                return false;
            }
            log.warn("Shopify webhook HMAC secret is not configured — accepting webhook without verification in non-strict mode.");
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
