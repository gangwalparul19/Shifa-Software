package com.shifa.oms.announcement.dto;

import com.shifa.oms.announcement.Announcement;

import java.time.LocalDateTime;

/**
 * A staff announcement banner (FEATURE-ROADMAP §8.4).
 *
 * @param id            the announcement id
 * @param message       the notice text
 * @param severity      info/success/warning/danger
 * @param active        whether it is currently shown to staff
 * @param createdByName  the admin who posted it (nullable)
 * @param createdAt     when it was posted
 */
public record AnnouncementResponse(
        Long id,
        String message,
        String severity,
        boolean active,
        String createdByName,
        LocalDateTime createdAt
) {

    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(
                a.getId(), a.getMessage(), a.getSeverity(), a.isActive(),
                a.getCreatedByName(), a.getCreatedAt());
    }
}
