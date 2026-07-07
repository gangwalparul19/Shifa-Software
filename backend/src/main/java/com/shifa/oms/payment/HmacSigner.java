package com.shifa.oms.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Small HMAC-SHA256 helper for the payment gateways. The signature scheme —
 * {@code HmacSHA256(gatewayOrderId + "|" + gatewayPaymentId, secret)} rendered
 * as lower-case hex — deliberately matches Razorpay's, so swapping the sandbox
 * for the real provider needs no change to the verification logic (only the
 * secret source differs: sandbox secret vs Razorpay key-secret).
 */
final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    /** Signs {@code gatewayOrderId|gatewayPaymentId} with the secret, hex-encoded. */
    static String sign(String secret, String gatewayOrderId, String gatewayPaymentId) {
        String payload = gatewayOrderId + "|" + gatewayPaymentId;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            // HmacSHA256 is guaranteed present on every JVM; a failure here is fatal.
            throw new IllegalStateException("Unable to compute HMAC signature", e);
        }
    }

    /** Constant-time comparison of an expected signature against a supplied one. */
    static boolean matches(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
