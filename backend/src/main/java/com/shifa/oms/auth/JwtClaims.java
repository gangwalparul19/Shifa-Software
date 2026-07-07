package com.shifa.oms.auth;

import java.time.Instant;

/**
 * The decoded, verified contents of a JWT issued by {@link JwtService}.
 *
 * @param userId    the subject user's id (claim {@code uid})
 * @param username  the subject user's username (claim {@code sub})
 * @param role      the subject user's {@link Role} (claim {@code role})
 * @param type      whether this is an access or refresh token (claim {@code typ})
 * @param issuedAt  when the token was issued (claim {@code iat})
 * @param expiresAt when the token expires (claim {@code exp})
 */
public record JwtClaims(
        Long userId,
        String username,
        Role role,
        TokenType type,
        Instant issuedAt,
        Instant expiresAt) {

    /** Builds the immutable {@link AuthPrincipal} represented by these claims. */
    public AuthPrincipal toPrincipal() {
        return new AuthPrincipal(userId, username, role);
    }
}
