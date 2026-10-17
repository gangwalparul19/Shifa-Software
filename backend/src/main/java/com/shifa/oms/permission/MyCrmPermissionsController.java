package com.shifa.oms.permission;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's effective CRM permission set (CRM expansion Phase 3, Req 19.2),
 * so the frontend can hide actions the caller lacks (belt-and-suspenders; the
 * backend still enforces each endpoint's guard). Any authenticated staff user
 * may read their own set.
 */
@RestController
@RequestMapping("/api/me/crm-permissions")
@PreAuthorize("isAuthenticated()")
public class MyCrmPermissionsController {

    private final PermissionService permissionService;
    private final CurrentUserService currentUserService;

    public MyCrmPermissionsController(PermissionService permissionService,
                                      CurrentUserService currentUserService) {
        this.permissionService = permissionService;
        this.currentUserService = currentUserService;
    }

    /** The authenticated caller's granted CRM permissions. */
    @GetMapping
    public List<String> myPermissions() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return List.copyOf(permissionService.effectivePermissions(actor));
    }
}
