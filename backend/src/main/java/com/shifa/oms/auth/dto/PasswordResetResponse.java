package com.shifa.oms.auth.dto;

import java.time.LocalDateTime;

/**
 * The one-time result of an administrator password reset. The generated
 * credential is intentionally returned only from the reset action and is never
 * part of {@link AdminUserResponse} list/detail payloads.
 */
public record PasswordResetResponse(
        AdminUserResponse user,
        String temporaryCredential,
        LocalDateTime expiresAt) {
}
