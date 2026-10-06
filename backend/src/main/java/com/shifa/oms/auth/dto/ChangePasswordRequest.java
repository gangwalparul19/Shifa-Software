package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service "change my password" payload
 * ({@code POST /api/me/password}). Used both for the forced change after an
 * admin reset and for a voluntary change by the signed-in user.
 *
 * <p>The new password must be at least 8 characters and contain at least one
 * uppercase letter, one digit, and one special character — enforced here with a
 * {@link Pattern} so a weak password is rejected with a 400 regardless of the
 * client. The confirm-match is checked on the client; the server only needs the
 * one authoritative new value.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        @Pattern(
                regexp = PASSWORD_PATTERN,
                message = "Password must have at least one uppercase letter, one number and one special character")
        String newPassword) {

    /**
     * Minimum 8 characters with at least one uppercase letter, one digit, and
     * one special (non-alphanumeric) character. Mirrored on the frontend.
     */
    public static final String PASSWORD_PATTERN =
            "^(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$";
}
