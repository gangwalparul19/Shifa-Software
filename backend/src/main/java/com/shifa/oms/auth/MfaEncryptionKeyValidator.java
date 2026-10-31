package com.shifa.oms.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production startup guard: MFA encryption must never silently run without a key. */
@Component
@Profile("prod")
public class MfaEncryptionKeyValidator {
    private final MfaSecretCrypto crypto;

    public MfaEncryptionKeyValidator(MfaSecretCrypto crypto) {
        this.crypto = crypto;
    }

    @PostConstruct
    void validate() {
        crypto.requireConfigured();
    }
}
