package com.shifa.oms.lead.dto;

import java.time.LocalDate;

/**
 * Set/clear follow-up payload for {@code PUT /api/leads/{id}/follow-up}
 * (Requirement 5.1, design &sect;API).
 *
 * <p>A {@code null} {@code followUpDate} clears the follow-up; a non-null value
 * sets/updates it. The value is applied verbatim by
 * {@code LeadService.setFollowUp}.
 */
public record FollowUpRequest(
        LocalDate followUpDate
) {
}
