package com.shifa.oms.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Grant / revoke a CRM permission to a role or a user (CRM expansion Phase 3,
 * Req 19.1). Exactly one of {@code role} / {@code userId} should be supplied.
 */
public record CrmPermissionRequest(
        @NotBlank @Size(max = 40) String permission,
        @Size(max = 20) String role,
        Long userId,
        Boolean granted) {
}
