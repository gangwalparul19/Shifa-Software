package com.shifa.oms.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM encryption for TOTP secrets. Plaintext is never logged or exposed. */
@Component
public class MfaSecretCrypto {

    private static final String PREFIX = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MfaSecretCrypto(@Value("${app.security.mfa.encryption-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    /** Test/local constructor; production uses the external property constructor. */
    public MfaSecretCrypto(String configuredKey, SecureRandom random) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
        if (random != null) {
            this.random.setSeed(random.generateSeed(32));
        }
    }

    public boolean isConfigured() {
        return !configuredKey.isBlank();
    }

    public void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("MFA encryption key is not configured");
        }
        if (configuredKey.length() < 32) {
            throw new IllegalStateException("MFA encryption key must contain at least 32 characters");
        }
    }

    public String encrypt(String plaintext) {
        requireConfigured();
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("MFA secret is empty");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + "." + b64(nonce) + "." + b64(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt MFA secret", e);
        }
    }

    public String decrypt(String stored) {
        requireConfigured();
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("Encrypted MFA secret is empty");
        }
        try {
            String[] parts = stored.split("\\.", -1);
            if (parts.length != 3 || !PREFIX.equals(parts[0])) {
                throw new IllegalStateException("Encrypted MFA secret format is invalid");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, Base64.getUrlDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Do not include provider details or ciphertext in the message.
            throw new IllegalStateException("Could not decrypt MFA secret", e);
        }
    }

    private SecretKeySpec key() {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configuredKey);
            if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
                raw = configuredKey.getBytes(StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            raw = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            raw = sha256(raw);
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive MFA encryption key", e);
        }
    }

    private static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
