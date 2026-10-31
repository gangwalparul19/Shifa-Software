package com.shifa.oms.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds the {@code app.security.jwt.*} configuration (see {@code application.yml}).
 *
 * <ul>
 *   <li>{@code secret} — HMAC signing secret (overridden per environment).</li>
 *   <li>{@code accessTokenTtl} — lifetime of short-lived access tokens (e.g. PT15M).</li>
 *   <li>{@code refreshTokenTtl} — lifetime of refresh tokens (e.g. P7D).</li>
 *   <li>{@code sessionTtl} — sliding authenticated-session idle timeout (e.g. PT30M).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public class JwtProperties {

    /** HMAC signing secret. MUST be overridden in production via an env var. */
    private String secret = "change-me-local-development-secret-change-me-please";

    /** Access-token time-to-live. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Refresh-token time-to-live. */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    /** Sliding idle timeout for server-authoritative authenticated sessions. */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /**
     * Lifetime of the short-lived MFA ticket issued between a correct password
     * and the TOTP step. Kept brief (a few minutes) so a stolen ticket is
     * useless almost immediately; it still grants no API access regardless.
     */
    private Duration mfaTicketTtl = Duration.ofMinutes(5);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getMfaTicketTtl() {
        return mfaTicketTtl;
    }

    public void setMfaTicketTtl(Duration mfaTicketTtl) {
        this.mfaTicketTtl = mfaTicketTtl;
    }
}
