-- =============================================================================
-- V94 — Meeting no-show & cancellation notes (CRM meeting close-out enhancement).
--
-- The client asked how to capture that a meeting did NOT happen (a no-show)
-- along with a note, and to capture a reason when cancelling. This introduces a
-- new MeetingStatus value `NO_SHOW` (added to the MeetingStatus enum in code —
-- no enum DDL needed, `meetings.status` is already a VARCHAR storing the enum
-- name). The existing `meetings.outcome` VARCHAR(1000) column is REUSED to carry
-- the free-text note across DONE / NO_SHOW / CANCELLED, so no new note column is
-- required.
--
-- Adds one nullable column:
--   * closed_at — when the meeting reached a terminal state (DONE / NO_SHOW /
--     CANCELLED). Additive/nullable — safe on existing rows (historical meetings
--     keep NULL).
-- =============================================================================

ALTER TABLE meetings
    ADD COLUMN closed_at DATETIME NULL AFTER outcome;
