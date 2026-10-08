package com.shifa.oms.auth;

/**
 * State of a self-service staff profile change request (stored as the enum name
 * in {@code staff_profile_change_requests.status}).
 *
 * <p>An employee's submission starts {@link #PENDING}; an admin then applies it
 * ({@link #APPROVED} — the proposed values are written to the user) or declines
 * it ({@link #REJECTED} — the user is left unchanged).
 */
public enum ChangeRequestStatus {

    /** Awaiting admin review; the user's profile is unchanged. */
    PENDING,

    /** Approved by an admin and applied to the user's profile. */
    APPROVED,

    /** Declined by an admin; nothing was applied. */
    REJECTED
}
