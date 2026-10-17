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
        LocalDateTime createdAt) {

    public static MeetingResponse from(MeetingEntity m) {
        return new MeetingResponse(
                m.getId(), m.getOwnerUserId(), m.getTitle(), m.getLeadId(), m.getCustomerMobile(),
                m.getStartAt(), m.getMode(), m.getLocation(), m.getNotes(), m.getStatus(),
                m.getOutcome(), m.getClosedAt(), m.getCreatedAt());
    }
}
