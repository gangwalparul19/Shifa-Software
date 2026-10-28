package com.shifa.oms.auth;

/** Distinguishes short-lived access tokens from longer-lived refresh tokens. */
public enum TokenType {
    ACCESS,
    REFRESH,
    /**
     * A short-lived, single-purpose MFA ticket issued after a correct password
     * when the user still owes a second factor. It proves "the password was
     * verified" so the client can complete the TOTP step, but grants no API
     * access: {@link JwtAuthenticationFilter} only accepts {@link #ACCESS}
     * tokens, so an MFA ticket can never be used as a bearer credential.
     */
    MFA
}
