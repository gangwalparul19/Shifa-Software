package com.shifa.oms.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * skipped so the endpoint is usable without a courier account. Production MUST
 * configure the secret; a blank secret is logged as a warning.
 */
@Component
public class HmacSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(HmacSignatureVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String secret;

    public HmacSignatureVerifier(CourierProperties properties) {
        this.secret = properties.webhookHmacSecret();
    }

    /**
     * Whether the signature over {@code rawBody} is valid.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the hex signature header value (may be {@code null})
     * @return {@code true} if valid, or if no secret is configured (dev mode)
     */
    public boolean isValid(byte[] rawBody, String signature) {
        if (secret == null || secret.isBlank()) {
            log.warn("Courier webhook HMAC secret is not configured — accepting webhook without verification.");
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
