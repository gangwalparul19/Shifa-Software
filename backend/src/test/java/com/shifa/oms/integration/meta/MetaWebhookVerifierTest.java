package com.shifa.oms.integration.meta;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetaWebhookVerifier} (spec {@code meta-lead-sync}, Req 2).
 */
class MetaWebhookVerifierTest {

    private static final String SECRET = "meta-app-secret-value";

    private static MetaProperties propsWithSecret(String secret) {
        return new MetaProperties(
                true, secret, "token", "verify", null,
                null, null, null, null, null, null, null, null, "MOCK");
    }

    private final MetaWebhookVerifier verifier = new MetaWebhookVerifier(propsWithSecret(SECRET));

    @Test
    void acceptsAValidSignatureWithTheSha256Prefix() {
        byte[] body = "{\"object\":\"page\"}".getBytes(StandardCharsets.UTF_8);
        String header = "sha256=" + MetaWebhookVerifier.sign(body, SECRET);
        assertThat(verifier.isValid(body, header)).isTrue();
    }

    @Test
    void acceptsAValidSignatureWithoutThePrefix() {
        byte[] body = "hello meta".getBytes(StandardCharsets.UTF_8);
        String header = MetaWebhookVerifier.sign(body, SECRET);
        assertThat(verifier.isValid(body, header)).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String header = "sha256=" + MetaWebhookVerifier.sign(body, SECRET);
        byte[] tampered = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.isValid(tampered, header)).isFalse();
    }

    @Test
    void rejectsAWrongSecretsSignature() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String header = "sha256=" + MetaWebhookVerifier.sign(body, "a-different-secret");
        assertThat(verifier.isValid(body, header)).isFalse();
    }

    @Test
    void rejectsNullOrBlankSignature() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.isValid(body, null)).isFalse();
        assertThat(verifier.isValid(body, "")).isFalse();
    }

    @Test
    void failsClosedWhenNoSecretConfigured() {
        MetaWebhookVerifier noSecret = new MetaWebhookVerifier(propsWithSecret(""));
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        String header = "sha256=" + MetaWebhookVerifier.sign(body, SECRET);
        assertThat(noSecret.isValid(body, header)).isFalse();
    }
}
