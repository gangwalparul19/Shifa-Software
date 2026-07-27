package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.VerificationStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Safe admin view of a staff/platform {@link User} for the user-management grid
 * ({@code /api/admin/users}). Deliberately omits the BCrypt password hash — the
 * credential is never exposed over the API.
 *
 * <p>Carries the full editable profile (contact + onboarding fields) so the
 * admin edit form can be pre-filled and the admin can change every detail of a
 * user, not just the role. {@code verificationStatus} is exposed read-only (the
 * verify/reject decision has its own audited workflow on the Salespeople page).
 */
public record AdminUserResponse(
        Long id,
        String username,
        String fullName,
        Role role,
        boolean active,
        LocalDateTime createdAt,
        String email,
        String mobile,
        LocalDate dateOfBirth,
        String address,
        LocalDate joinedOn,
        IdProofType idProofType,
        String idProofNumber,
        VerificationStatus verificationStatus,
        Long teamLeadId) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt(),
                user.getEmail(),
                user.getMobile(),
                user.getDateOfBirth(),
                user.getAddress(),
                user.getJoinedOn(),
                user.getIdProofType(),
                user.getIdProofNumber(),
                user.getVerificationStatus(),
                user.getTeamLeadId());
    }
}
