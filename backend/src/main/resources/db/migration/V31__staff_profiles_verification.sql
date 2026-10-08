-- Staff onboarding & salesperson profiles with ID verification.
--
-- Previously the platform only stored a salesperson's username + full name. To
-- keep a proper record of each staff member and verify their identity BEFORE
-- they are onboarded, we extend the existing `users` table with profile and
-- ID-verification columns. Additive/nullable so it is safe on the seeded data.
--
-- The uploaded ID document itself is kept in the pluggable object store
-- (StorageService: LOCAL / DB / S3) exactly like payment screenshots; only the
-- opaque storage key is persisted here (`id_proof_key`).
ALTER TABLE users
    ADD COLUMN date_of_birth       DATE          NULL,
    ADD COLUMN address             VARCHAR(500)  NULL,
    ADD COLUMN joined_on           DATE          NULL,
    ADD COLUMN id_proof_type       VARCHAR(30)   NULL,
    ADD COLUMN id_proof_number     VARCHAR(60)   NULL,
    ADD COLUMN id_proof_key        VARCHAR(255)  NULL,
    ADD COLUMN verification_status VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    ADD COLUMN verification_note   VARCHAR(500)  NULL,
    ADD COLUMN verified_at         DATETIME      NULL,
    ADD COLUMN verified_by         BIGINT        NULL;

-- Existing accounts (seeded staff + any customers) are treated as already
-- verified so current logins are not flagged as pending after this migration.
-- New staff created from now on default to PENDING and must be verified.
UPDATE users SET verification_status = 'VERIFIED';

CREATE INDEX ix_users_role_verification ON users (role, verification_status);
