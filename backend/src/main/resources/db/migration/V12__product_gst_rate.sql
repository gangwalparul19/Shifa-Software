-- =============================================================================
-- Shifa Herbal Remedies OMS - per-product GST rate (V12)
-- Adds an optional `gst_rate` (percent) to `products` so GST tax invoices can
-- use a per-product rate, falling back to the settings-level default GST rate
-- when NULL. Backward compatible: existing products keep NULL and use the
-- default. Additive only.
-- =============================================================================

-- Optional per-product GST rate percent (e.g. 5.00 / 12.00 / 18.00). NULL means
-- "use the settings-level default gst_rate_percent".
ALTER TABLE products ADD COLUMN gst_rate DECIMAL(5,2) NULL AFTER hsn_code;
