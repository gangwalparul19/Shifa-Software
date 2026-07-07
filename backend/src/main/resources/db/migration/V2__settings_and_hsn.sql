-- =============================================================================
-- Shifa Herbal Remedies OMS - application settings + product HSN (V2)
-- Adds a single-row `app_settings` table (company + GST configuration) and an
-- optional `hsn_code` column to `products`. Engine/charset/money conventions
-- match V1 (InnoDB, utf8mb4, DECIMAL). GST is DISABLED by default so invoices
-- keep rendering as the current simple invoice until an Admin turns GST on.
-- =============================================================================

-- ---------------------------------------------------------- app_settings ----
-- Single-row configuration table. The application always loads / seeds the row
-- with id = 1; a create-on-missing safeguard exists in the service layer too.
CREATE TABLE app_settings (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    gst_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    gstin               VARCHAR(20)   NULL,
    legal_name          VARCHAR(200)  NOT NULL DEFAULT 'Shifa Herbal Remedies',
    address_line        VARCHAR(250)  NULL,
    city                VARCHAR(100)  NULL,
    state               VARCHAR(100)  NULL,
    state_code          VARCHAR(4)    NULL,
    gst_rate_percent    DECIMAL(5,2)  NOT NULL DEFAULT 5.00,
    prices_include_gst  BOOLEAN       NOT NULL DEFAULT TRUE,
    invoice_footer_note VARCHAR(500)  NULL,
    contact_phone       VARCHAR(20)   NULL,
    contact_email       VARCHAR(120)  NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_app_settings PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the single settings row with sensible defaults (GST disabled).
INSERT INTO app_settings (id, gst_enabled, legal_name, gst_rate_percent, prices_include_gst)
VALUES (1, FALSE, 'Shifa Herbal Remedies', 5.00, TRUE);

-- --------------------------------------------------- products.hsn_code -------
-- Optional HSN code per product, surfaced on GST tax invoices when present.
ALTER TABLE products ADD COLUMN hsn_code VARCHAR(20) NULL AFTER sale_price;
