package com.shifa.oms.auth;

import java.time.Instant;
import java.util.Objects;

/** Immutable authenticated identity plus the server-side idle-session sid. */
public record AuthPrincipal(Long userId, String username, Role role,
                            Instant sessionStartedAt, String sessionId) {

    /** Backward-compatible identity-only constructor. */
    public AuthPrincipal(Long userId, String username, Role role) {
        this(userId, username, role, null, null);
    }

    /** Backward-compatible constructor used by legacy callers that do not carry a sid. */
    public AuthPrincipal(Long userId, String username, Role role, Instant sessionStartedAt) {
        this(userId, username, role, sessionStartedAt, null);
    }

    /** New convenience constructor for an idle-session identity. */
    public AuthPrincipal(Long userId, String username, Role role, String sessionId) {
        this(userId, username, role, null, sessionId);
    }

    public AuthPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
    }
}
