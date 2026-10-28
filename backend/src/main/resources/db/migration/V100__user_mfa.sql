-- Multi-factor authentication (TOTP / Google Authenticator) for platform users.
--
-- Additive and nullable so it is safe on existing rows: every user starts with
-- MFA OFF (mfa_enabled = FALSE) and no enrolled secret, so logins are unchanged
-- until an admin turns MFA on per user (ADMIN-first rollout).
--
--   mfa_enabled     whether MFA is REQUIRED for this user. Flip to TRUE to put a
--                   user in the MFA flow; if they have not enrolled a secret yet
--                   they are walked through enrollment at their next login
--                   (they are never locked out by enabling the flag alone).
--   mfa_secret      the Base32-encoded TOTP shared secret (RFC 4648), set when
--                   the user enrolls and the authenticator app scans the QR.
--                   NULL = not enrolled. Never exposed over the API after
--                   enrollment completes.
--   mfa_enrolled_at when the user confirmed their first code (enrollment done).
--                   NULL until confirmed; a user is "MFA-active" only when
--                   mfa_enabled = TRUE AND mfa_secret IS NOT NULL AND this is set.

ALTER TABLE users
    ADD COLUMN mfa_enabled     BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret      VARCHAR(64)   NULL,
    ADD COLUMN mfa_enrolled_at DATETIME      NULL;
