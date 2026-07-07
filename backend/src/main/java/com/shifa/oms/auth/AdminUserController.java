package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.dto.AdminUserResponse;
import com.shifa.oms.auth.dto.CreateUserRequest;
import com.shifa.oms.auth.dto.ResetPasswordRequest;
import com.shifa.oms.auth.dto.UpdateUserRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * Admin staff/platform user management ({@code /api/admin/users}, Feature A).
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403 (standard error envelope). Responses use
 * the safe {@link AdminUserResponse}, which never carries the password hash.
 *
 * <ul>
 *   <li>{@code GET /api/admin/users} — list all users;</li>
 *   <li>{@code POST /api/admin/users} — create a user (201);</li>
 *   <li>{@code PUT /api/admin/users/{id}} — update full name, role, active;</li>
 *   <li>{@code POST /api/admin/users/{id}/reset-password} — set a new password;</li>
 *   <li>{@code POST /api/admin/users/{id}/activate} — enable login;</li>
 *   <li>{@code POST /api/admin/users/{id}/deactivate} — disable login.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final AuditService auditService;

    public AdminUserController(AdminUserService adminUserService, AuditService auditService) {
        this.adminUserService = adminUserService;
        this.auditService = auditService;
    }

    /** Lists all users, newest first, for the management grid. */
    @GetMapping
    public List<AdminUserResponse> list() {
        return adminUserService.list();
    }

    /** Creates a user; a duplicate username is rejected with a 409. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUserResponse create(@Valid @RequestBody CreateUserRequest request) {
        AdminUserResponse response = adminUserService.create(request);
        auditService.record(AuditActions.USER_CREATED, AuditActions.ENTITY_USER,
                String.valueOf(response.id()),
                "Created user " + response.username() + " (" + response.role() + ")");
        return response;
    }

    /** Updates a user's full name, role, and active flag (never username/password). */
    @PutMapping("/{id}")
    public AdminUserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        AdminUserResponse response = adminUserService.update(id, request);
        auditService.record(AuditActions.USER_UPDATED, AuditActions.ENTITY_USER,
                String.valueOf(id),
                "Updated user " + response.username() + " (role " + response.role()
                        + ", active " + response.active() + ")");
        return response;
    }

    /** Re-encodes and sets a new password for the user. */
    @PostMapping("/{id}/reset-password")
    public AdminUserResponse resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        AdminUserResponse response = adminUserService.resetPassword(id, request);
        auditService.record(AuditActions.USER_PASSWORD_RESET, AuditActions.ENTITY_USER,
                String.valueOf(id), "Reset password for user " + response.username());
        return response;
    }

    /** Activates a user so they can authenticate again. */
    @PostMapping("/{id}/activate")
    public AdminUserResponse activate(@PathVariable Long id) {
        AdminUserResponse response = adminUserService.activate(id);
        auditService.record(AuditActions.USER_ACTIVATED, AuditActions.ENTITY_USER,
                String.valueOf(id), "Activated user " + response.username());
        return response;
    }

    /** Deactivates a user so they can no longer authenticate. */
    @PostMapping("/{id}/deactivate")
    public AdminUserResponse deactivate(@PathVariable Long id) {
        AdminUserResponse response = adminUserService.deactivate(id);
        auditService.record(AuditActions.USER_DEACTIVATED, AuditActions.ENTITY_USER,
                String.valueOf(id), "Deactivated user " + response.username());
        return response;
    }
}
