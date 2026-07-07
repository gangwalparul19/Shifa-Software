package com.shifa.oms.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Refresh token submitted to {@code POST /api/auth/refresh}. */
public record RefreshRequest(
        @NotBlank(message = "refreshToken is required") String refreshToken) {
}
