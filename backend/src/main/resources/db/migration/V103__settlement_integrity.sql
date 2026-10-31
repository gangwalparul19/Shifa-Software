-- Settlement integrity metadata and safe matching indexes. Additive and compatible with V98/V99/V102.
ALTER TABLE settlement_allocations ADD COLUMN target_key VARCHAR(100) NULL;
ALTER TABLE settlement_allocations ADD COLUMN reason VARCHAR(500) NULL;
UPDATE settlement_allocations SET target_key = CONCAT(target_type, ':', COALESCE(payment_id, order_id, receivable_id)) WHERE target_key IS NULL;
ALTER TABLE settlement_allocations MODIFY target_key VARCHAR(100) NOT NULL;
CREATE UNIQUE INDEX uq_settlement_allocations_line_target_key ON settlement_allocations (settlement_line_id, target_key);
ALTER TABLE settlement_lines ADD COLUMN date_defaulted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX ix_settlement_allocations_target_key ON settlement_allocations (target_key);
-- UTR uniqueness is enforced at the application boundary for legacy duplicate-safe data;
-- no destructive unique index is added until an operator preflight confirms zero duplicates.
CREATE INDEX ix_payments_account_utr_norm ON payments (bank_account_id, utr);
