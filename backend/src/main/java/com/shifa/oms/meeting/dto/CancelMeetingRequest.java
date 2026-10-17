package com.shifa.oms.meeting.dto;

import jakarta.validation.constraints.Size;

/**
 * Cancel-meeting request (CRM meeting close-out enhancement; Req 9.2). Carries an
 * OPTIONAL reason stored in {@code meetings.outcome}; the body may be
 * absent/empty, in which case the meeting is simply marked CANCELLED.
 */
public record CancelMeetingRequest(
        @Size(max = 1000) String reason) {
}
