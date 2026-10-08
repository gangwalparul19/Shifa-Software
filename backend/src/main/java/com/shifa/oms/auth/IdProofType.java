package com.shifa.oms.auth;

/**
 * The kinds of government identity document accepted as onboarding proof for a
 * staff member (stored as the enum name in {@code users.id_proof_type}).
 */
public enum IdProofType {

    /** Aadhaar card (UIDAI). */
    AADHAAR,

    /** Permanent Account Number card. */
    PAN,

    /** Driving licence. */
    DRIVING_LICENSE,

    /** Voter ID / EPIC. */
    VOTER_ID,

    /** Passport. */
    PASSPORT,

    /** Any other accepted document. */
    OTHER
}
