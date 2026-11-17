package com.shifa.oms.meeting.dto;

import com.shifa.oms.meeting.MeetingEntity;
import com.shifa.oms.meeting.MeetingMode;
import com.shifa.oms.meeting.MeetingStatus;

import java.time.LocalDateTime;

/** Read-model projection of a {@link MeetingEntity} (CRM expansion Phase 2, Task 11). */
public record MeetingResponse(
        Long id,
        Long ownerUserId,
        String title,
        Long leadId,
        String customerMobile,
        LocalDateTime startAt,
        MeetingMode mode,
        String location,
        String notes,
        MeetingStatus status,
        String outcome,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        /** The owner's display name, filled in for list views; null when unknown. */
        String ownerName) {

    public static MeetingResponse from(MeetingEntity m) {
        return new MeetingResponse(
                m.getId(), m.getOwnerUserId(), m.getTitle(), m.getLeadId(), m.getCustomerMobile(),
                m.getStartAt(), m.getMode(), m.getLocation(), m.getNotes(), m.getStatus(),
                m.getOutcome(), m.getClosedAt(), m.getCreatedAt(), null);
    }

    /** Copy with the owner's display name set. */
    public MeetingResponse withOwnerName(String name) {
        return new MeetingResponse(id, ownerUserId, title, leadId, customerMobile, startAt,
                mode, location, notes, status, outcome, closedAt, createdAt, name);
    }
}
