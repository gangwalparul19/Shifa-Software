-- =============================================================================
-- V65 — Multiple payment screenshots per order.
--
-- Before this migration an order could carry exactly ONE payment proof, held in
-- the single-valued `orders.payment_screenshot_key` column (V1). Salespeople
-- routinely have more than one proof for a single order — a part payment today
-- and the balance tomorrow, a UPI receipt plus a bank transfer confirmation, or
-- simply two screenshots because the amount did not fit one screen — and had to
-- pick one and drop the rest.
--
-- This migration is ADDITIVE and backward compatible:
--   * the new `order_payment_screenshots` child table holds the FULL ordered set
--     of proofs for an order (one row per uploaded file);
--   * `orders.payment_screenshot_key` is KEPT and continues to hold the FIRST
--     (primary) proof, so every existing read path stays correct without change
--     — the "screenshot required when money was received" rule
--     (PaymentCalculator), the `paymentScreenshotAvailable` booleans on the
--     order / approval-queue / payment-verification projections, the existing
--     `GET /api/orders/{id}/payment-screenshot` endpoint, and the invoice and
--     admin-exception read paths all keep working untouched.
--
-- Only the opaque storage key is persisted, never the bytes — identical to the
-- existing convention (the bytes live in the configured StorageService: local
-- filesystem, the `stored_files` table, or S3, per `app.storage.provider`).
--
-- Uniqueness is scoped to (order_id, storage_key) rather than storage_key alone:
-- the same proof cannot be attached to the SAME order twice, but two different
-- orders MAY legitimately reference the same stored object — the staged-upload
-- API hands the client an opaque key and nothing stops it being submitted with
-- more than one order. A global UNIQUE(storage_key) would therefore risk aborting
-- this migration on real data, and would turn a harmless client repeat into a 500
-- at order creation.
-- =============================================================================

CREATE TABLE order_payment_screenshots (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    filename     VARCHAR(255) NULL,
    content_type VARCHAR(100) NULL,
    byte_size    BIGINT       NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_order_payment_screenshots PRIMARY KEY (id),
    CONSTRAINT uq_order_payment_screenshots_order_key UNIQUE (order_id, storage_key),
    CONSTRAINT fk_order_payment_screenshots_order
        FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_order_payment_screenshots_order
    ON order_payment_screenshots (order_id, sort_order);

-- ---------------------------------------------------------------- backfill ---
-- Carry every existing single screenshot across as the order's first (primary)
-- proof, so historical orders immediately render in the new multi-proof viewer
-- rather than appearing to have lost their screenshot. filename/content_type/
-- byte_size are unknown for historical rows (they were never captured) and stay
-- NULL; the read path falls back to the values reported by the storage layer.
INSERT INTO order_payment_screenshots (order_id, storage_key, sort_order)
SELECT o.id, o.payment_screenshot_key, 0
FROM orders o
WHERE o.payment_screenshot_key IS NOT NULL
  AND TRIM(o.payment_screenshot_key) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM order_payment_screenshots s
      WHERE s.order_id = o.id
        AND s.storage_key = o.payment_screenshot_key
  );
