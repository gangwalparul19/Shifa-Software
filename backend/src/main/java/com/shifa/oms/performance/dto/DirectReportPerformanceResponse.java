package com.shifa.oms.performance.dto;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.VerificationStatus;

import java.time.LocalDate;

/**
 * A team lead's authorised drill-down into one direct report's performance.
 *
 * <p>The embedded work profile deliberately contains only fields needed to
 * identify and contact the direct report. Sensitive staff-profile, credential,
 * identity-document, and storage-key fields are never exposed here.
 */
public record DirectReportPerformanceResponse(
        DirectReportProfile profile,
        SalespersonPerformanceDetail performance
) {

    public static DirectReportPerformanceResponse of(User user,
                                                      SalespersonPerformanceDetail performance) {
        return new DirectReportPerformanceResponse(DirectReportProfile.from(user), performance);
    }

    /** Safe, work-relevant direct-report profile exposed with the drill-down. */
    public record DirectReportProfile(
            Long id,
            String fullName,
            String username,
            String email,
            String mobile,
            LocalDate joinedOn,
            boolean active,
            VerificationStatus verificationStatus
    ) {
        public static DirectReportProfile from(User user) {
            return new DirectReportProfile(
                    user.getId(),
                    user.getFullName(),
                    user.getUsername(),
                    user.getEmail(),
                    user.getMobile(),
                    user.getJoinedOn(),
                    user.isActive(),
                    user.getVerificationStatus());
        }
    }
}
