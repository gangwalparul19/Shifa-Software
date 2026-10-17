package com.shifa.oms.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Record-outcome request for a meeting (CRM expansion Phase 2, Task 11; Req 9.2):
 * marks the meeting DONE with a free-text outcome.
 */
public record MeetingOutcomeRequest(
        @NotBlank @Size(max = 1000) String outcome) {
}
