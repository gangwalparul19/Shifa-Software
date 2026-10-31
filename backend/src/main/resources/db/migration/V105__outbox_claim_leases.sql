-- Fenced outbox leases for multi-instance integration drainers.
-- Rows remain PENDING while leased so an expired lease can be recovered after a crash.
ALTER TABLE outbox
    ADD COLUMN lease_owner VARCHAR(128) NULL,
    ADD COLUMN lease_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME NULL;

CREATE INDEX ix_outbox_claim_due
    ON outbox (event_type, status, next_attempt_at, lease_until, created_at, id);
