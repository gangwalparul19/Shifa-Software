package com.shifa.oms.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCryptoTest {

    private static final String KEY = "test-mfa-key-that-is-at-least-32-bytes-long";

    @Test
    void roundTripUsesAuthenticatedRandomCiphertext() {
        MfaSecretCrypto crypto = new MfaSecretCrypto(KEY);
        String first = crypto.encrypt("JBSWY3DPEHPK3PXP");
        String second = crypto.encrypt("JBSWY3DPEHPK3PXP");

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(crypto.decrypt(second)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(first).doesNotContain("JBSWY3DPEHPK3PXP");
    }

    @Test
    void wrongKeyAndTamperingFailClosed() {
        MfaSecretCrypto crypto = new MfaSecretCrypto(KEY);
        String encrypted = crypto.encrypt("JBSWY3DPEHPK3PXP");

        assertThatThrownBy(() -> new MfaSecretCrypto("different-key-that-is-also-long-enough").decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKeyCannotEncryptOrDecrypt() {
        MfaSecretCrypto crypto = new MfaSecretCrypto("");
        assertThatThrownBy(() -> crypto.encrypt("secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt("v1.a.b")).isInstanceOf(IllegalStateException.class);
    }
}
