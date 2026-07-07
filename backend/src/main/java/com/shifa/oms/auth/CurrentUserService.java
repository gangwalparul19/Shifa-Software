package com.shifa.oms.auth;

import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads the authenticated {@link AuthPrincipal} from the Spring Security context.
 *
 * <p>This is the single place other modules use to learn "who is making this
 * request", which drives both authorization decisions and salesperson scoping
 * (Req 5.4, 5.5). It is intentionally thin so it can be consumed by the order
 * module (task 9) without dragging in web concerns.
 */
@Service
public class CurrentUserService {

    /** The current principal, or empty when the request is unauthenticated. */
    public Optional<AuthPrincipal> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /**
     * The current principal, or an {@link ApiException} (401) when unauthenticated.
     * Use this from endpoints that require an identity to function.
     */
    public AuthPrincipal requireCurrentUser() {
        return currentUser().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication is required to access this resource."));
    }
}
