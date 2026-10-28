package com.shifa.oms.auth.dto;

/**
 * Returned by {@code POST /api/auth/login} (HTTP 200) when the password was
 * correct but a second factor is still owed. It is deliberately NOT a 401 so the
 * client's token-refresh interceptor does not try to silently refresh it.
 *
 * <p>Two shapes, distinguished by {@link #status}:
 * <ul>
 *   <li>{@code MFA_REQUIRED} — the user is enrolled; the client prompts for the
 *       current authenticator code and calls {@code /api/auth/mfa/verify}.
 *       {@link #otpauthUri} and {@link #secret} are {@code null}.</li>
 *   <li>{@code MFA_ENROLL} — MFA is on for the user but they have not enrolled
 *       yet; the client shows the QR code built from {@link #otpauthUri} (and the
 *       manual {@link #secret}) to scan, then calls
 *       {@code /api/auth/mfa/enroll/confirm}.</li>
 * </ul>
 *
 * @param status      {@code "MFA_REQUIRED"} or {@code "MFA_ENROLL"}
 * @param mfaTicket   short-lived signed ticket proving the password step, echoed
 *                    back on the verify/confirm call
 * @param otpauthUri  the {@code otpauth://} provisioning URI for the QR code
 *                    (enrollment only; {@code null} otherwise)
 * @param secret      the Base32 secret for manual entry (enrollment only;
 *                    {@code null} otherwise)
 */
public record MfaChallengeResponse(
        String status,
        String mfaTicket,
        String otpauthUri,
        String secret) {

    public static final String STATUS_REQUIRED = "MFA_REQUIRED";
    public static final String STATUS_ENROLL = "MFA_ENROLL";

    /** A challenge for an already-enrolled user (code entry only). */
    public static MfaChallengeResponse required(String mfaTicket) {
        return new MfaChallengeResponse(STATUS_REQUIRED, mfaTicket, null, null);
    }

    /** An enrollment challenge carrying the QR provisioning URI + manual secret. */
    public static MfaChallengeResponse enroll(String mfaTicket, String otpauthUri, String secret) {
        return new MfaChallengeResponse(STATUS_ENROLL, mfaTicket, otpauthUri, secret);
    }
}
