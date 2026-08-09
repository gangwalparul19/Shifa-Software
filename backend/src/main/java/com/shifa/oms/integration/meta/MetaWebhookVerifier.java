package com.shifa.oms.integration.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies the signature on an inbound Meta Lead Ads webhook (spec
 * {@code meta-lead-sync}, Req 2).
 *
 * <p>Meta signs the raw request body with the App secret and sends a
 * <b>hex</b>-encoded HMAC-SHA256 in {@code X-Hub-Signature-256}, prefixed with
 * {@code sha256=} (e.g. {@code sha256=a1b2c3...}). This differs from the Shopify
 * verifier, which expects a base64 digest with no prefix.
 *
 * <p>Like the Shopify verifier it <b>fails closed</b> on a missing secret — an
 * unauthenticated lead-creation endpoint is worse than a broken integration — and
 * compares in constant time via {@link MessageDigest#isEqual}.
 */
@Component
public class MetaWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final MetaProperties properties;

    public MetaWebhookVerifier(MetaProperties properties) {
        this.properties = properties;
    }

    /**
     * Whether the signature over {@code rawBody} is valid.
     *
     * @param rawBody         the exact request body bytes as received; re-serialising
     *                        the parsed JSON would change whitespace and invalidate it
     * @param signatureHeader the {@code X-Hub-Signature-256} value, possibly null/blank
     */
    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (!properties.hasAppSecret()) {
            log.warn("Meta app secret is not configured — rejecting the webhook delivery. "
                    + "Set app.meta.app-secret to enable ingestion.");
            return false;
        }
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String provided = signatureHeader.trim();
        if (provided.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            provided = provided.substring(PREFIX.length());
        }

        byte[] expected = sign(rawBody, properties.appSecret()).getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, providedBytes);
    }

    /**
     * Computes the lowercase hex HMAC-SHA256 Meta would send for a body (without the
     * {@code sha256=} prefix). Used by tests and any replay path.
     */
    public static String sign(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute the Meta HMAC signature", e);
        }
    }
}
