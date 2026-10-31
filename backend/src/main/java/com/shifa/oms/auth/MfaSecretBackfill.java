package com.shifa.oms.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Converts V100 plaintext TOTP rows to AES-GCM ciphertext after migrations complete. */
@Component
public class MfaSecretBackfill {
    private final UserRepository userRepository;
    private final MfaSecretCrypto crypto;

    public MfaSecretBackfill(UserRepository userRepository, MfaSecretCrypto crypto) {
        this.userRepository = userRepository;
        this.crypto = crypto;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void encryptLegacySecrets() {
        for (User user : userRepository.findUsersWithLegacyMfaSecret()) {
            String secret = user.getMfaSecret();
            if (secret == null || secret.isBlank()) {
                continue;
            }
            user.setMfaSecretCiphertext(crypto.encrypt(secret));
            user.clearLegacyMfaSecret();
            user.setRuntimeMfaSecret(null);
            userRepository.save(user);
        }
    }
}
