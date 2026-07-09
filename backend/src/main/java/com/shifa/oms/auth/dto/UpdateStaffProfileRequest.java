package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Admin payload to capture/update a staff member's onboarding profile
 * ({@code PUT /api/admin/staff/{id}}).
 *
 * <p>Only profile fields are editable here — the username, role, active flag and
 * password stay on the existing {@code /api/admin/users} endpoints. All fields
 * except the full name are optional so details can be filled in progressively.
 */
public record UpdateStaffProfileRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

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
