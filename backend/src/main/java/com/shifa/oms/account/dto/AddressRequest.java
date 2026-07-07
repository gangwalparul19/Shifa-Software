package com.shifa.oms.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a saved customer address
 * ({@code POST/PUT /api/account/addresses}). Mirrors the checkout validation:
 * required address fields, a 10-digit mobile, and a 6-digit postal code. The
 * {@code label} (e.g. Home/Work) is optional. {@code makeDefault} asks the
 * server to mark this address the checkout default.
 */
public record AddressRequest(
        @Size(max = 60, message = "label must be at most 60 characters")
        String label,

        @NotBlank(message = "fullName is required")
        @Size(max = 100, message = "fullName must be at most 100 characters")
        String fullName,

        @NotBlank(message = "mobile is required")
        @Pattern(regexp = "\\d{10}", message = "mobile must be exactly 10 digits")
        String mobile,

        @NotBlank(message = "addressLine is required")
        @Size(max = 250, message = "addressLine must be at most 250 characters")
        String addressLine,

        @NotBlank(message = "city is required")
        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @NotBlank(message = "state is required")
        @Size(max = 100, message = "state must be at most 100 characters")
        String state,

        @NotBlank(message = "postalCode is required")
        @Pattern(regexp = "\\d{6}", message = "postalCode must be exactly 6 digits")
        String postalCode,

        boolean makeDefault) {
}
