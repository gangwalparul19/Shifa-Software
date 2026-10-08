package com.shifa.oms.auth;

/**
 * The identity-verification state of a staff member (stored as the enum name in
 * {@code users.verification_status}).
 *
 * <p>New staff start {@link #PENDING}; an admin reviews their uploaded ID proof
 * and moves them to {@link #VERIFIED} (cleared to work) or {@link #REJECTED}
 * (proof not acceptable). Uploading a fresh document resets the state back to
 * {@link #PENDING} so it is reviewed again.
 */
public enum VerificationStatus {

    /** Awaiting admin review of the ID proof. */
    PENDING,

    /** ID proof reviewed and accepted — the staff member is cleared. */
    VERIFIED,

    /** ID proof reviewed and rejected — needs a valid document. */
    REJECTED
}
