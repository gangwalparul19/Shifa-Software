package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.VerificationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin payload to record an ID-verification decision for a staff member
 * ({@code POST /api/admin/staff/{id}/verify}).
 *
 * <p>The {@code status} must be {@code VERIFIED} or {@code REJECTED}; a note is
 * optional (typically used to explain a rejection).
 */
public record VerifyStaffRequest(
        @NotNull(message = "status is required")
        VerificationStatus status,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note) {
}
