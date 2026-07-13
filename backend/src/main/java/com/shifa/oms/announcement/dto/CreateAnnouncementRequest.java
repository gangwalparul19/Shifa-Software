package com.shifa.oms.announcement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to post a staff announcement ({@code POST /api/admin/announcements},
 * FEATURE-ROADMAP §8.4).
 */
public record CreateAnnouncementRequest(
        @NotBlank(message = "message is required")
        @Size(max = 500, message = "message must be at most 500 characters")
        String message,

        @Pattern(regexp = "info|success|warning|danger",
                message = "severity must be one of info, success, warning, danger")
        String severity
) {
}
