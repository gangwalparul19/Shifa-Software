package com.shifa.oms.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the JWT signing secret under the production profile.
 *
 * <p>The {@code prod} profile MUST supply a real {@code JWT_SECRET}. Booting
 * production with the committed development default (or a too-short secret)
 * would let anyone who can read the source forge valid tokens, so we abort
 * startup rather than run insecurely. Non-prod profiles keep the convenient
 * local default so developers need no setup.</p>
 */
@Component
@Profile("prod")
public class JwtSecretValidator {

    /** The committed development default — never acceptable in production. */
    static final String KNOWN_DEFAULT_SECRET =
            "change-me-local-development-secret-change-me-please";

    /** HMAC-SHA256 needs at least a 256-bit (32-byte) key to be meaningful. */
    static final int MIN_SECRET_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtSecretValidator.class);

    private final JwtProperties properties;

    public JwtSecretValidator(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is not configured. Set JWT_SECRET to a long random value "
                            + "(e.g. `openssl rand -base64 48`) before starting in production.");
        }
        if (KNOWN_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT secret is still the committed development default. Set JWT_SECRET to a "
                            + "unique long random value before starting in production.");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret is too short (" + secret.length() + " chars). Use at least "
                            + MIN_SECRET_LENGTH + " characters for HMAC-SHA256.");
        }
        log.info("JWT secret validated for production profile (length={} chars).", secret.length());
    }
}
