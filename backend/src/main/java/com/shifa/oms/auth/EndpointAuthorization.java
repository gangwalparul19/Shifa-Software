package com.shifa.oms.auth;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Pure authorization decision for a role-protected endpoint (Req 5.2, 5.3).
 *
 * <p>This mirrors, in testable pure logic, the decision Spring Security makes via
 * the filter chain and {@code @PreAuthorize}: an unauthenticated caller is denied
 * (Req 5.2), and an authenticated caller whose role is not permitted for the
 * endpoint is denied (Req 5.3). It exists so the enforcement rule can be
 * property-tested without standing up the full HTTP stack, and documents the
 * authority matrix each endpoint applies.
 */
@Component
public class EndpointAuthorization {

    /**
     * Whether a caller may invoke an endpoint that permits {@code allowedRoles}.
     *
     * @param principal    the caller, or empty when unauthenticated
     * @param allowedRoles the roles permitted for the endpoint
     * @return {@code true} only if the caller is authenticated and holds a
     *         permitted role
     */
    public boolean isAllowed(Optional<AuthPrincipal> principal, Set<Role> allowedRoles) {
        if (principal == null || principal.isEmpty()) {
            return false; // Req 5.2 — authentication required.
        }
        return allowedRoles.contains(principal.get().role()); // Req 5.3 — role must be permitted.
    }
}
