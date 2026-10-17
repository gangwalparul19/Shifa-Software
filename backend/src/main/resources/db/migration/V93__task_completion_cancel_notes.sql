-- =============================================================================
-- V93 — Task completion & cancellation notes (CRM task close-out enhancement).
--
-- The client wanted to close a CRM task with a proper outcome, not just a bare
-- "Done": an optional completion note when finishing a task, and a first-class
-- Cancel with an optional reason (TaskStatus already has OPEN/DONE/CANCELLED —
-- this wires the CANCELLED path end-to-end).
--
-- Adds three nullable columns to `tasks`:
--   * completion_note — optional free-text captured when a task is completed.
--   * cancel_reason   — optional free-text captured when a task is cancelled.
--   * cancelled_at    — when the task reached CANCELLED (mirrors completed_at).
--
-- Additive/nullable — safe on existing rows (historical tasks keep NULLs). No
-- enum DDL: `tasks.status` is already a VARCHAR storing the TaskStatus name.
-- =============================================================================

ALTER TABLE tasks
    ADD COLUMN completion_note VARCHAR(1000) NULL AFTER completed_at,
    ADD COLUMN cancel_reason VARCHAR(1000) NULL AFTER completion_note,
    ADD COLUMN cancelled_at DATETIME NULL AFTER cancel_reason;
