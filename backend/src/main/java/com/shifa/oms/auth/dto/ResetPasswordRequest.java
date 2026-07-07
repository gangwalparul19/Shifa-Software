package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to reset a user's password
 * ({@code POST /api/admin/users/{id}/reset-password}). The new password is
 * re-encoded with the shared {@code PasswordEncoder}; the same minimum length
 * as self-registration applies.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = 6, max = 100, message = "newPassword must be at least 6 characters")
        String newPassword) {
}
