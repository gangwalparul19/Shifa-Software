package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.ChangeRequestStatus;
import com.shifa.oms.auth.ProfileChangeRequest;
import com.shifa.oms.auth.User;

import java.time.LocalDateTime;

/**
 * A staff profile change request for the employee's own view and the admin
 * approval queue: the {@code proposed} values, the staff member's {@code current}
 * values (for a diff), and review metadata.
 */
public record ProfileChangeRequestResponse(
        Long id,
        Long userId,
        String username,
        String fullNameOfUser,
        ChangeRequestStatus status,
        ProfileFields current,
        ProfileFields proposed,
        String requestNote,
        String reviewNote,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt) {

    public static ProfileChangeRequestResponse from(ProfileChangeRequest request, User user) {
        return new ProfileChangeRequestResponse(
                request.getId(),
                request.getUserId(),
                user.getUsername(),
                user.getFullName(),
                request.getStatus(),
                ProfileFields.ofUser(user),
                ProfileFields.ofRequest(request),
                request.getRequestNote(),
                request.getReviewNote(),
                request.getRequestedAt(),
                request.getReviewedAt());
    }
}
