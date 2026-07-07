-- =============================================================================
-- Shifa Herbal Remedies OMS - company logo + default low-stock threshold (V13)
-- Adds a `logo_object_key` to `app_settings` (opaque storage key of an uploaded
-- company logo, rendered on invoices + labels) and a settings-level default
-- `low_stock_threshold` (used when a product has no per-product override).
-- Additive only.
-- =============================================================================

-- Opaque storage key of the uploaded company logo (via StorageService); NULL
-- means "no logo configured", so PDFs fall back to the text brand.
ALTER TABLE app_settings ADD COLUMN logo_object_key VARCHAR(255) NULL AFTER contact_email;

-- Settings-level default low-stock threshold. A tracked product is "low stock"
-- when 0 < stock_quantity <= threshold (per-product override wins when present).
ALTER TABLE app_settings ADD COLUMN low_stock_threshold INT NOT NULL DEFAULT 5 AFTER logo_object_key;
