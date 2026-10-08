package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.ChangeRequestStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin decision on a staff profile change request
 * ({@code POST /api/admin/staff/change-requests/{id}/review}).
 *
 * <p>{@code status} must be {@code APPROVED} (apply the proposed values to the
 * user) or {@code REJECTED} (leave the user unchanged); a note is optional.
 */
public record ReviewProfileChangeRequest(
        @NotNull(message = "status is required")
        ChangeRequestStatus status,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note) {
}
