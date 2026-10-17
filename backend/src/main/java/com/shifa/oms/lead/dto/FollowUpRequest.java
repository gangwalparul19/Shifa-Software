package com.shifa.oms.lead.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Set/clear follow-up payload for {@code PUT /api/leads/{id}/follow-up}
 * (Requirement 5.1, design &sect;API; CRM expansion Req 6).
 *
 * <p>A {@code null} {@code followUpDate} clears the follow-up; a non-null value
 * sets/updates it. {@code followUpTime} is an optional time-of-day appended for
 * the CRM expansion (Req 6) — a {@code null} time keeps today's date-only
 * behaviour. Both are applied by {@code LeadService.setFollowUp}, which re-arms
 * the reminder.
 */
public record FollowUpRequest(
        LocalDate followUpDate,
        LocalTime followUpTime
) {
}
