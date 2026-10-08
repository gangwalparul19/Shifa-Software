package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.dto.ChangePasswordRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service "change my password" for the signed-in user
 * ({@code POST /api/me/password}).
 *
 * <p>Used both for the forced change after an admin reset (the user signed in
 * with the temporary password {@code Welcome@123} and must now choose their own
 * strong password) and for a voluntary change. The caller's identity comes from
 * the authenticated principal, so no old password is required — the valid
 * session proves identity. The new password's complexity (min 8 chars, 1
 * uppercase, 1 number, 1 special) is enforced by {@link ChangePasswordRequest}.
 *
 * <p>Returns a fresh token pair so the client continues without the force-change
 * flag; the clearing of {@code mustChangePassword} happens in
 * {@link AuthService#changeMyPassword}.
 */
@RestController
@RequestMapping("/api/me/password")
@PreAuthorize("isAuthenticated()")
public class MyPasswordController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public MyPasswordController(AuthService authService,
                                CurrentUserService currentUserService,
                                AuditService auditService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** Sets a new password for the signed-in user and returns a fresh token pair. */
    @PostMapping
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        TokenResponse response = authService.changeMyPassword(principal.userId(), request);
        auditService.record(AuditActions.USER_PASSWORD_CHANGED, AuditActions.ENTITY_USER,
                String.valueOf(principal.userId()), "Changed own password");
        return response;
    }
}
