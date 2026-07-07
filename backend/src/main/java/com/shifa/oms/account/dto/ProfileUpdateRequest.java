package com.shifa.oms.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Profile update payload ({@code PUT /api/account/profile}). A customer may
 * update their display name, email, and mobile only — never their role or
 * username. Email/mobile are optional; when a mobile is supplied it must be 10
 * digits.
 */
public record ProfileUpdateRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @Size(max = 150, message = "email must be at most 150 characters")
        String email,

        @Pattern(regexp = "^(\\d{10})?$", message = "mobile must be exactly 10 digits")
        String mobile) {
}
