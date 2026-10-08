package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.VerificationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Rich admin view of a staff member for the "All salespeople" directory and the
 * profile/verification drawer (staff onboarding &amp; ID verification).
 *
 * <p>Carries the profile and verification fields but never the password hash or
 * the raw ID document (only {@code hasIdProof} indicates a document exists; the
 * bytes are fetched separately via the guarded id-proof endpoint).
 */
public record StaffProfileResponse(
        Long id,
        String username,
        String fullName,
        Role role,
        boolean active,
        String email,
        String mobile,
        LocalDate dateOfBirth,
        String address,
        LocalDate joinedOn,
        IdProofType idProofType,
        String idProofNumber,
        boolean hasIdProof,
        boolean hasProfileImage,
        VerificationStatus verificationStatus,
        String verificationNote,
        LocalDateTime verifiedAt,
        LocalDateTime createdAt) {

    public static StaffProfileResponse from(User user) {
        return new StaffProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.isActive(),
                user.getEmail(),
                user.getMobile(),
                user.getDateOfBirth(),
                user.getAddress(),
                user.getJoinedOn(),
                user.getIdProofType(),
                user.getIdProofNumber(),
                user.getIdProofKey() != null && !user.getIdProofKey().isBlank(),
                user.getProfileImageKey() != null && !user.getProfileImageKey().isBlank(),
                user.getVerificationStatus(),
                user.getVerificationNote(),
                user.getVerifiedAt(),
                user.getCreatedAt());
    }
}
