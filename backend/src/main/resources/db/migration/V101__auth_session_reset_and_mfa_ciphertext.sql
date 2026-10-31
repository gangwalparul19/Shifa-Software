-- A2/A3/A4 additive auth hardening.
-- Existing plaintext MFA secrets remain readable only long enough for the application
-- to encrypt them lazily with MFA_ENCRYPTION_KEY; new writes use ciphertext only.
ALTER TABLE users
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN password_reset_expires_at DATETIME NULL,
    ADD COLUMN mfa_secret_ciphertext VARCHAR(512) NULL;
