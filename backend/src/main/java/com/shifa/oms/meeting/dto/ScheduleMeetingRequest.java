package com.shifa.oms.meeting.dto;

import com.shifa.oms.meeting.MeetingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Schedule / reschedule request for a meeting (CRM expansion Phase 2, Task 11;
 * Req 9.1, 9.2). A title + start time are required; mode/location/notes and a
 * lead/customer link are optional. An ADMIN/TEAM_LEAD may set {@code ownerUserId}
 * for a team member.
 */
public record ScheduleMeetingRequest(
        @NotBlank @Size(max = 200) String title,
        Long leadId,
        @Size(max = 10) String customerMobile,
        @NotNull LocalDateTime startAt,
        MeetingMode mode,
        @Size(max = 200) String location,
        @Size(max = 1000) String notes,
        Long ownerUserId) {
}
