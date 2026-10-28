package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Second-factor submission for {@code POST /api/auth/mfa/verify} and
 * {@code POST /api/auth/mfa/enroll/confirm}.
 *
 * @param mfaTicket the ticket returned by the login challenge
 * @param code      the current 6-digit authenticator code
 */
public record MfaVerifyRequest(
        @NotBlank(message = "mfaTicket is required") String mfaTicket,
        @NotBlank(message = "code is required") String code) {
}
