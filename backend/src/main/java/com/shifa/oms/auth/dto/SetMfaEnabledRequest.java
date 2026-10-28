package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Admin toggle for a user's MFA (TOTP) requirement
 * ({@code POST /api/admin/users/{id}/mfa}).
 *
 * @param enabled whether MFA is required for the user
 */
public record SetMfaEnabledRequest(
        @NotNull(message = "enabled is required") Boolean enabled) {
}
