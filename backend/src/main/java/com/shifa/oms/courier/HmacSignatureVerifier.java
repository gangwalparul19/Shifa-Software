package com.shifa.oms.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies the HMAC-SHA256 signature on inbound courier webhooks
 * (design "Webhook auth"; Req 13.*). The courier signs the raw request body with
 * the shared secret {@code app.courier.webhook-hmac-secret} and sends the hex
 * digest in a header; this verifier recomputes it and compares in constant time.
 *
 * <p>When no secret is configured (local dev default is blank), verification is
 * skipped only in non-strict mode so the endpoint is usable without a courier
 * account. Strict mode rejects unsigned requests; production also fails startup
 * when the secret is missing.</p>
 */
@Component
public class HmacSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(HmacSignatureVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String secret;
    private final boolean strict;

    /** Spring constructor. Strict mode is enabled in production and opt-in elsewhere. */
    @Autowired
    public HmacSignatureVerifier(CourierProperties properties,
                                 @Value("${app.webhooks.hmac-strict:false}") boolean strict) {
        this.secret = properties.webhookHmacSecret();
        this.strict = strict;
    }

    /** Explicit non-strict constructor retained for local/demo/test callers. */
    public HmacSignatureVerifier(CourierProperties properties) {
        this(properties, false);
    }

    /**
     * Whether the signature over {@code rawBody} is valid.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the hex signature header value (may be {@code null})
     * @return {@code true} if valid, or if no secret is configured in explicit
     *         non-strict mode (local/demo/test)
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (secret == null || secret.isBlank()) {
            if (strict) {
                log.warn("Courier webhook HMAC secret is not configured — rejecting the webhook in strict mode.");
                return false;
            }
            log.warn("Courier webhook HMAC secret is not configured — accepting webhook without verification in non-strict mode.");
            return true;
        }
        if (signature == null || signature.isBlank() || rawBody == null) {
            return false;
        }
        String expected = hex(hmac(rawBody, secret));
        String provided = signature.trim().toLowerCase();
        // Constant-time comparison to avoid timing side channels.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** Computes the hex HMAC-SHA256 of a payload with the configured secret (test/demo helper). */
    public static String sign(byte[] rawBody, String secret) {
        return hex(hmac(rawBody, secret));
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

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
