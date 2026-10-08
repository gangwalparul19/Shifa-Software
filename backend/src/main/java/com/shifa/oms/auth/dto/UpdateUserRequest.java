package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import com.shifa.oms.auth.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Admin payload to update an existing user's profile
 * ({@code PUT /api/admin/users/{id}}).
 *
 * <p>The admin may change every detail of the account here — full name, role,
 * active flag, contact details and onboarding profile. The username stays
 * immutable and the password is changed via the dedicated reset endpoint.
 *
 * <p>All fields after {@code active} are optional so partial profiles are valid;
 * blank strings are normalised to {@code null} in the service.
 */
public record UpdateUserRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @NotNull(message = "role is required")
        Role role,

        @NotNull(message = "active is required")
        Boolean active,

        @Email(message = "email must be a valid email address")
        @Size(max = 150, message = "email must be at most 150 characters")
        String email,

        @Pattern(regexp = "^$|^[0-9]{10}$", message = "mobile must be a 10-digit number")
        String mobile,

        LocalDate dateOfBirth,

        @Size(max = 500, message = "address must be at most 500 characters")
        String address,

        LocalDate joinedOn,

        IdProofType idProofType,

        @Size(max = 60, message = "idProofNumber must be at most 60 characters")
        String idProofNumber) {
}
