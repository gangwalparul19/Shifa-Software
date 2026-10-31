package com.shifa.oms.auth;

import java.time.Instant;

/** Decoded, signed JWT contents. The sid identifies the server-side idle session. */
public record JwtClaims(
        Long userId,
        String username,
        Role role,
        long sessionVersion,
        TokenType type,
        Instant issuedAt,
        Instant expiresAt,
        Instant sessionStartedAt,
        Instant sessionExpiresAt,
        String sessionId) {

    /** Backward-compatible constructor for direct test fixtures and old callers. */
    public JwtClaims(Long userId, String username, Role role, long sessionVersion,
                     TokenType type, Instant issuedAt, Instant expiresAt) {
        this(userId, username, role, sessionVersion, type, issuedAt, expiresAt,
                issuedAt, issuedAt == null ? null : issuedAt.plusSeconds(30 * 60L), null);
    }

    /** Compatibility constructor for the previous nine-claim representation. */
    public JwtClaims(Long userId, String username, Role role, long sessionVersion,
                     TokenType type, Instant issuedAt, Instant expiresAt,
                     Instant sessionStartedAt, Instant sessionExpiresAt) {
        this(userId, username, role, sessionVersion, type, issuedAt, expiresAt,
                sessionStartedAt, sessionExpiresAt, null);
    }

    public AuthPrincipal toPrincipal() {
        return new AuthPrincipal(userId, username, role, sessionStartedAt, sessionId);
    }
}
