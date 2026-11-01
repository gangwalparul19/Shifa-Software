package com.shifa.oms.auth.dto;

import java.time.LocalDateTime;

/**
 * The one-time result of an administrator password reset. The generated
 * credential is intentionally returned only from the reset action and is never
 * part of {@link AdminUserResponse} list/detail payloads.
 *
 * @param emailedTo masked address (for example {@code j***@gmail.com}) the
 *                  credential was also emailed to, or {@code null} when it was
 *                  not emailed; the credential is always returned here regardless
 */
public record PasswordResetResponse(
        AdminUserResponse user,
        String temporaryCredential,
        LocalDateTime expiresAt,
        String emailedTo) {

    /** Result with no email sent. */
    public PasswordResetResponse(AdminUserResponse user, String temporaryCredential, LocalDateTime expiresAt) {
        this(user, temporaryCredential, expiresAt, null);
    }

    /** Copy recording which masked address the credential was emailed to. */
    public PasswordResetResponse withEmailedTo(String maskedAddress) {
        return new PasswordResetResponse(user, temporaryCredential, expiresAt, maskedAddress);
    }
}
