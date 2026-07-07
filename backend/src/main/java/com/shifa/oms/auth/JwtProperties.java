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
}
