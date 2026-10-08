-- =============================================================================
-- V81 — Force-password-change tracking for users (admin password reset flow).
--
-- When an admin resets a user's password, the account is set to the fixed
-- temporary password "Welcome@123" and flagged so the user MUST choose a new
-- strong password (min 8 chars, 1 uppercase, 1 number, 1 special) on their next
-- login before they can use the app. The reset time and a running count are
-- recorded so the admin panel can show "when" and "how many times" a user's
-- password was reset.
--
-- Additive and backfill-safe:
--   must_change_password  — FALSE for every existing account (no one is forced
--                           to change on this deploy; the flag is only raised
--                           by a future admin reset).
--   password_reset_at     — NULL until the first reset.
--   password_reset_count  — 0 for every existing account.
-- =============================================================================

ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN password_reset_at    DATETIME  NULL,
    ADD COLUMN password_reset_count INT       NOT NULL DEFAULT 0;
