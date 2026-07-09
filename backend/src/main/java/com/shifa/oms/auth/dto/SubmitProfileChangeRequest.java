package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A staff member's self-service request to change their own profile details
 * ({@code POST /api/me/profile/change-request}). Submitting does not change the
 * profile — it queues the values for admin approval.
 */
public record SubmitProfileChangeRequest(
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

        IdProofType idProofType,

        @Size(max = 60, message = "idProofNumber must be at most 60 characters")
        String idProofNumber,

        @Size(max = 500, message = "note must be at most 500 characters")
        String requestNote) {
}
