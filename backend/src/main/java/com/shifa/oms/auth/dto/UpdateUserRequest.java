package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to update an existing user's profile
 * ({@code PUT /api/admin/users/{id}}).
 *
 * <p>Only the full name, role, and active flag are editable here; the username
 * is immutable and the password is changed via the dedicated reset endpoint.
 */
public record UpdateUserRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @NotNull(message = "role is required")
        Role role,

        @NotNull(message = "active is required")
        Boolean active) {
}
