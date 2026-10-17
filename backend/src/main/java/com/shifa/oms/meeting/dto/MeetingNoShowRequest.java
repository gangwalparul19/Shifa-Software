package com.shifa.oms.meeting.dto;

import jakarta.validation.constraints.Size;

/**
 * No-show request for a meeting (CRM meeting close-out enhancement; Req 9.2):
 * marks the meeting NO_SHOW (it did not happen) with an OPTIONAL note stored in
 * {@code meetings.outcome}. The body may be absent/empty.
 */
public record MeetingNoShowRequest(
        @Size(max = 1000) String note) {
}
