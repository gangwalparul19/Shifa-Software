package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * Admin payload accepted for backward compatibility. The server ignores the
 * optional value and generates a random credential instead.
 */
public record ResetPasswordRequest(
        @Size(max = 100, message = "newPassword must be at most 100 characters")
        String newPassword) {
}
