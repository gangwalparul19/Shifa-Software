package com.shifa.oms.auth;

import java.util.Objects;

/**
 * The authenticated principal placed in the Spring Security context for the
 * duration of a request. Carries just enough identity to drive authorization
 * and salesperson scoping: the user id, username, and {@link Role}.
 *
 * <p>Kept deliberately small and immutable so it is safe to read from the
 * security context anywhere in the request thread.
 */
public record AuthPrincipal(Long userId, String username, Role role) {

    public AuthPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
    }
}
