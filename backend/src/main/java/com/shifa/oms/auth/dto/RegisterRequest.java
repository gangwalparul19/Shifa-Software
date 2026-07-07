package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public customer self-registration payload for {@code POST /api/auth/register}.
 *
 * <p>The customer supplies their full name, a 10-digit mobile (also used as the
 * login username when no email is given), an optional email, and a password. A
 * customer account is created with role {@code CUSTOMER}; the endpoint returns
 * the same token pair shape as login so the storefront can auto-login.
 *
 * <p>Validation is intentionally loose (per the roadmap): password minimum
 * length and a 10-digit mobile. The chosen {@code username} is derived by the
 * service (email when present, else mobile).
 */
public record RegisterRequest(
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must be at most 150 characters")
        String fullName,

        @NotBlank(message = "mobile is required")
        @Pattern(regexp = "\\d{10}", message = "mobile must be exactly 10 digits")
        String mobile,

        @Size(max = 150, message = "email must be at most 150 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 6, max = 100, message = "password must be at least 6 characters")
        String password) {
}
