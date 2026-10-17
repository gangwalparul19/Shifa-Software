package com.shifa.oms.permission;

import com.shifa.oms.permission.dto.CrmPermissionRequest;
import com.shifa.oms.permission.dto.CrmPermissionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin CRM-permission management (CRM expansion Phase 3, Req 19.1). ADMIN-only:
 * grant/revoke the defined CRM permissions to roles or individual users layered
 * on top of the fixed base roles.
 */
@RestController
@RequestMapping("/api/admin/crm-permissions")
@PreAuthorize("hasRole('ADMIN')")
public class CrmPermissionController {

    private final PermissionService permissionService;

    public CrmPermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** The full set of defined permission names (for the management UI). */
    @GetMapping("/definitions")
    public List<String> definitions() {
        return PermissionService.ALL_PERMISSIONS;
    }

    /** All configured grants. */
    @GetMapping
    public List<CrmPermissionResponse> list() {
        return permissionService.listGrants().stream()
                .map(CrmPermissionResponse::from)
                .toList();
    }

    /** Grant / revoke a permission to a role or user (upsert). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrmPermissionResponse grant(@Valid @RequestBody CrmPermissionRequest request) {
        boolean granted = request.granted() == null || request.granted();
        return CrmPermissionResponse.from(permissionService.grant(
                request.permission(), request.role(), request.userId(), granted));
    }

    /** Delete a grant by id (reverts that role/user to the resolved default). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        permissionService.revoke(id);
    }
}
