-- =============================================================================
-- V77 — Opaque per-order tracking token (ENHANCEMENT 2.2).
--
-- The existing public tracking endpoint keys off the human order code
-- (SHR-yyyyMMdd-XXXX), which is guessable/enumerable. This adds an opaque,
-- unguessable token so a customer-facing "track your order" link
-- (/track/{token}) exposes exactly one order and cannot be walked to others.
--
-- `tracking_token` is UNIQUE and NULLable (a unique index permits many NULLs in
-- MySQL). Every EXISTING order is backfilled with a random 32-hex-char token
-- (two concatenated dash-stripped UUIDs, truncated to 32) so historical orders
-- are immediately trackable; new orders get a token from the entity @PrePersist.
-- Additive and safe on seeded data.
-- =============================================================================
ALTER TABLE orders
    ADD COLUMN tracking_token VARCHAR(40) NULL AFTER shopify_order_id;

-- Backfill existing rows with a unique opaque token (32 hex chars).
UPDATE orders
SET tracking_token = SUBSTRING(
        CONCAT(REPLACE(UUID(), '-', ''), REPLACE(UUID(), '-', '')), 1, 32)
WHERE tracking_token IS NULL;

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_tracking_token UNIQUE (tracking_token);
