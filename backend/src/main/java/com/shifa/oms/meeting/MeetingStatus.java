package com.shifa.oms.meeting;

/**
 * A meeting's lifecycle state (CRM expansion Phase 2, Task 11; Req 9.2).
 * {@code NO_SHOW} captures that a scheduled meeting did not happen (a note is
 * stored in {@code meetings.outcome}); it is a distinct terminal state from a
 * recorded-outcome {@code DONE} and a deliberate {@code CANCELLED}.
 */
public enum MeetingStatus {
    SCHEDULED, DONE, NO_SHOW, CANCELLED
}
