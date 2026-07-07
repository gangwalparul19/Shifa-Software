-- =============================================================================
-- Shifa Herbal Remedies OMS - Wave 3 invoice numbering + line tax snapshot (V15)
--
-- ROADMAP Wave 3 admin features:
--   1. Settings expansion: a configurable invoice-number prefix, an atomic
--      running invoice-number series (dedicated `invoice_sequence` table),
--      invoice terms & conditions text, bank details, and the configurable set
--      of GST slabs the business uses.
--   2. Per-line HSN + GST rate snapshot on `line_items` so a historical invoice
--      shows the correct per-line HSN/tax even if the product later changes.
--
-- Engine / charset / money conventions match V1 (InnoDB, utf8mb4, DECIMAL).
-- Additive only (no drops); safe to run on existing data.
-- =============================================================================

-- ------------------------------------------------- app_settings (Feature 1) --
-- Invoice-number prefix (e.g. 'SHR/24-25/'); the running series is allocated
-- from the invoice_sequence table below and formatted as <prefix><zero-padded no>.
ALTER TABLE app_settings ADD COLUMN invoice_number_prefix VARCHAR(40)   NULL AFTER low_stock_threshold;

-- Multi-line terms & conditions rendered on the invoice footer/section.
ALTER TABLE app_settings ADD COLUMN invoice_terms         VARCHAR(2000) NULL AFTER invoice_number_prefix;

-- Bank details rendered on the invoice when present.
ALTER TABLE app_settings ADD COLUMN bank_name             VARCHAR(120)  NULL AFTER invoice_terms;
ALTER TABLE app_settings ADD COLUMN bank_account_name     VARCHAR(120)  NULL AFTER bank_name;
ALTER TABLE app_settings ADD COLUMN bank_account_number   VARCHAR(40)   NULL AFTER bank_account_name;
ALTER TABLE app_settings ADD COLUMN bank_ifsc             VARCHAR(20)   NULL AFTER bank_account_number;
ALTER TABLE app_settings ADD COLUMN bank_branch           VARCHAR(120)  NULL AFTER bank_ifsc;

-- Configurable set of GST rates the business uses, as a comma-separated list
-- (e.g. '0,5,12,18,28'); surfaced via settings so the product form / order entry
-- can offer them. The existing single gst_rate_percent / gst_enabled keep working.
ALTER TABLE app_settings ADD COLUMN gst_slabs             VARCHAR(100)  NULL AFTER bank_branch;

-- ---------------------------------------------- invoice_sequence (Feature 1) --
-- Single-row running counter for invoice numbers. Allocation increments
-- next_value under a row lock (SELECT ... FOR UPDATE) inside the allocating
-- transaction, so concurrent allocations serialize and never duplicate a number.
CREATE TABLE invoice_sequence (
    id          BIGINT NOT NULL,
    next_value  BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT pk_invoice_sequence PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the single counter row (starts allocating at 1).
INSERT INTO invoice_sequence (id, next_value) VALUES (1, 1);

-- --------------------------------------------------- orders.invoice_number ---
-- The allocated invoice number, persisted on first invoice generation so
-- re-downloads are stable (the number is never re-allocated on subsequent PDF
-- fetches). NULL until the first invoice is generated. Unique when present
-- (MySQL permits multiple NULLs under a UNIQUE index).
ALTER TABLE orders ADD COLUMN invoice_number VARCHAR(60) NULL AFTER order_code;
CREATE UNIQUE INDEX ux_orders_invoice_number ON orders (invoice_number);

-- ------------------------------------------- line_items HSN + GST (Feature 2) --
-- Snapshot the product's HSN code + GST rate percent onto the order line at
-- creation, so the invoice shows the correct per-line HSN/tax even if the
-- product's HSN/rate later changes. NULL on legacy rows (pre-Wave-3 orders):
-- the invoice falls back to the current product values for those.
ALTER TABLE line_items ADD COLUMN hsn_code VARCHAR(20)  NULL AFTER product_name;
ALTER TABLE line_items ADD COLUMN gst_rate DECIMAL(5,2) NULL AFTER hsn_code;
