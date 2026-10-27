-- Weekly courier COD remittance: persisted import batches + per-line settlement
-- records, and partial-settlement support on receivables.
--
-- The authoritative COD settler is RemittanceImportService/RemittanceRowProcessor
-- (NOT the V98 settlement_batches ledger, which only records evidence). Before
-- this migration a remittance import returned only transient per-import counts
-- and could not partially settle a short remittance. This migration:
--   1. Adds receivables.settled_amount so a receivable can be marked settled for
--      the amount actually remitted while any shortfall stays outstanding.
--   2. Adds remittance_batches + remittance_batch_lines so every committed import
--      is a queryable record powering the weekly settlement report (KPIs, filters,
--      per-order details, shortfall highlighting).
-- All additive; safe on seeded data.

-- 1) Partial-settlement amount on the receivable. NULL for historical rows:
--    a legacy settled receivable is treated as fully settled (settled_amount = amount);
--    an unsettled one as 0 settled. New settlements always write this.
ALTER TABLE receivables
    ADD COLUMN settled_amount DECIMAL(12,2) NULL AFTER settled;

-- Backfill: existing settled rows are, by the old all-or-nothing model, fully settled.
UPDATE receivables SET settled_amount = amount WHERE settled = TRUE AND settled_amount IS NULL;
UPDATE receivables SET settled_amount = 0.00  WHERE settled = FALSE AND settled_amount IS NULL;

-- 2) One row per committed remittance import.
CREATE TABLE remittance_batches (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    original_filename     VARCHAR(255)  NULL,
    -- The courier's own batch reference parsed from the filename when present
    -- (e.g. "Remittance Batch ID - 488 ..."), for the operator's cross-reference.
    external_reference    VARCHAR(120)  NULL,
    -- The predominant remitted date in the file (used to bucket the week); falls
    -- back to the import date when the file carries no remitted dates.
    remitted_date         DATE          NULL,
    total_rows            INT           NOT NULL DEFAULT 0,
    settled_count         INT           NOT NULL DEFAULT 0,
    partial_count         INT           NOT NULL DEFAULT 0,
    mismatch_count        INT           NOT NULL DEFAULT 0,
    already_settled_count INT           NOT NULL DEFAULT 0,
    not_found_count       INT           NOT NULL DEFAULT 0,
    no_receivable_count   INT           NOT NULL DEFAULT 0,
    error_count           INT           NOT NULL DEFAULT 0,
    -- Money totals across the file (two decimals).
    total_remitted        DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    total_settled         DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    total_shortfall       DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_by            BIGINT        NULL,
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_remittance_batches PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_remittance_batches_date ON remittance_batches (remitted_date, id);
CREATE INDEX ix_remittance_batches_created ON remittance_batches (created_at, id);

-- 3) One row per data line in a committed remittance import, carrying the matched
--    order, expected vs remitted amount, the shortfall, and the per-row outcome.
CREATE TABLE remittance_batch_lines (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    batch_id              BIGINT        NOT NULL,
    source_row_number     INT           NOT NULL,
    awb                   VARCHAR(80)   NULL,
    client_order_id       VARCHAR(120)  NULL,
    customer_name         VARCHAR(160)  NULL,
    resolved_order_id     BIGINT        NULL,
    resolved_order_code   VARCHAR(40)   NULL,
    expected_amount       DECIMAL(12,2) NULL,
    remitted_amount       DECIMAL(12,2) NULL,
    settled_amount        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    shortfall_amount      DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status                VARCHAR(30)   NOT NULL,
    message               VARCHAR(500)  NULL,
    remitted_date         DATE          NULL,
    CONSTRAINT pk_remittance_batch_lines PRIMARY KEY (id),
    CONSTRAINT fk_remittance_lines_batch FOREIGN KEY (batch_id) REFERENCES remittance_batches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_remittance_lines_batch ON remittance_batch_lines (batch_id, source_row_number);
CREATE INDEX ix_remittance_lines_order ON remittance_batch_lines (resolved_order_id);
CREATE INDEX ix_remittance_lines_status ON remittance_batch_lines (status, id);
