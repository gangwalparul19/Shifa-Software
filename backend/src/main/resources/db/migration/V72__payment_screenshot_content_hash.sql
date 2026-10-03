-- =============================================================================
-- Shifa Herbal Remedies OMS - payment-screenshot content hash (V72)
--
-- Duplicate-payment-proof detection (payment-verifier enhancement): a fraudulent
-- or mistaken order sometimes reuses the SAME payment screenshot that was already
-- used on another order. We store a SHA-256 of each proof's bytes so the payment
-- verification queue can flag when the same image appears on more than one order.
--
-- Additive + nullable: existing rows (and proofs uploaded before this) simply have
-- no hash and are never flagged. Indexed so the "other orders with this hash"
-- lookup is cheap.
-- =============================================================================

ALTER TABLE order_payment_screenshots
    ADD COLUMN content_hash VARCHAR(64) NULL AFTER byte_size;

CREATE INDEX ix_order_payment_screenshots_hash
    ON order_payment_screenshots (content_hash);
