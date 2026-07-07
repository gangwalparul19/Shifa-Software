package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to create a staff/platform user
 * ({@code POST /api/admin/users}).
 *
 * <p>Validation mirrors the existing auth conventions: a required, length-bound
 * username, a password of at least 6 characters (matching self-registration),
 * a required full name, and a role that must be one of the {@link Role} enum
 * values (Jackson rejects unknown names with a 400). Username uniqueness is
 * enforced in the service layer.
 */
public record CreateUserRequest(
        @NotBlank(message = "username is required")
        @Size(max = 100, message = "username must be at most 100 characters")
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 6, max = 100, message = "password must be at least 6 characters")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @NotNull(message = "role is required")
        Role role,

        /** Whether the account is active on creation; null defaults to active. */
        Boolean active) {
}
