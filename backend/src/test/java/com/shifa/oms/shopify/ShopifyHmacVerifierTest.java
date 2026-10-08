package com.shifa.oms.shopify;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShopifyHmacVerifier}: it must accept a correct base64
 * Shopify signature, reject a wrong/garbled one, and (dev convenience) skip
 * verification only when no secret is configured.
 */
class ShopifyHmacVerifierTest {

    private static final String SECRET =
            "67c3379ed64bd77b48e5e45beedb8080574c68aa4bd917ddf00b09117af93419";

    private ShopifyHmacVerifier verifier(String secret) {
        return new ShopifyHmacVerifier(new ShopifyProperties(secret));
    }

    @Test
    void acceptsCorrectBase64Signature() {
        byte[] body = "{\"id\":123,\"total_price\":\"100.00\"}".getBytes(StandardCharsets.UTF_8);
        String signature = ShopifyHmacVerifier.sign(body, SECRET);

        assertThat(verifier(SECRET).isValid(body, signature)).isTrue();
    }

    @Test
    void rejectsWrongSignature() {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);
        String wrong = Base64.getEncoder().encodeToString("not-the-right-digest".getBytes(StandardCharsets.UTF_8));

        assertThat(verifier(SECRET).isValid(body, wrong)).isFalse();
    }

    @Test
    void rejectsWhenBodyTamperedAfterSigning() {
        byte[] original = "{\"id\":123,\"total_price\":\"100.00\"}".getBytes(StandardCharsets.UTF_8);
        String signature = ShopifyHmacVerifier.sign(original, SECRET);
        byte[] tampered = "{\"id\":123,\"total_price\":\"1.00\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier(SECRET).isValid(tampered, signature)).isFalse();
    }

    @Test
    void rejectsMissingSignatureWhenSecretConfigured() {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier(SECRET).isValid(body, null)).isFalse();
        assertThat(verifier(SECRET).isValid(body, "  ")).isFalse();
    }

    @Test
    void rejectsNonBase64Signature() {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier(SECRET).isValid(body, "%%%not-base64%%%")).isFalse();
    }

    @Test
    void skipsVerificationWhenNoSecretConfigured() {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier("").isValid(body, null)).isTrue();
        assertThat(verifier(null).isValid(body, "anything")).isTrue();
    }
}
