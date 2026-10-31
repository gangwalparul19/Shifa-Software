package com.shifa.oms.courier;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused coverage for courier hex HMAC verification and strict-mode behavior. */
class HmacSignatureVerifierTest {

    private static final String SECRET = "courier-webhook-test-secret";

    private HmacSignatureVerifier verifier(String secret) {
        return new HmacSignatureVerifier(properties(secret));
    }

    private CourierProperties properties(String secret) {
        return new CourierProperties("MOCK", null, null, secret,
                Duration.ofSeconds(10), 3, Duration.ofSeconds(30), "Shifa Express");
    }

    @Test
    void acceptsCorrectHexSignature() {
        byte[] body = "{\"awb\":\"AWB-1\",\"status\":\"delivered\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatureVerifier.sign(body, SECRET);

        assertThat(verifier(SECRET).isValid(body, signature)).isTrue();
        assertThat(verifier(SECRET).isValid(body, signature.toUpperCase())).isTrue();
    }

    @Test
    void rejectsWrongMissingAndTamperedSignatures() {
        byte[] original = "{\"awb\":\"AWB-1\",\"status\":\"delivered\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatureVerifier.sign(original, SECRET);
        byte[] tampered = "{\"awb\":\"AWB-1\",\"status\":\"rto\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier(SECRET).isValid(original, "deadbeef")).isFalse();
        assertThat(verifier(SECRET).isValid(original, null)).isFalse();
        assertThat(verifier(SECRET).isValid(tampered, signature)).isFalse();
    }

    @Test
    void strictModeRejectsBlankSecretButExplicitNonStrictModeOptsOut() {
        byte[] body = "{\"awb\":\"AWB-1\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(new HmacSignatureVerifier(properties(""), true)
                .isValid(body, null)).isFalse();
        assertThat(new HmacSignatureVerifier(properties("   "), true)
                .isValid(body, "anything")).isFalse();
        assertThat(verifier("").isValid(body, null)).isTrue();
    }
}
