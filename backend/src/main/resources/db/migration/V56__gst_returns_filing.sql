-- =============================================================================
-- Shifa Herbal Remedies OMS - GST Returns & Filing (Phase 3) tables (V56)
--
-- Spec: gst-returns-filing (Phase 3). Adds the filing-status lifecycle, period
-- locking, immutable versioned filing snapshots, and GST-way amendment routing
-- around the already-shipped GST computation (GstEngine) and GSTR-1 assembly
-- (Gstr1Builder / Gstr1ReturnService). ADDITIVE ONLY - three new tables plus two
-- new nullable columns on app_settings; no existing table, column, or row is
-- changed and no backfill is required (Reqs 10.3, 10.4, A4).
--
--   * return_filings     - one filing-status row per (period, return type). A
--                          missing row denotes NOT_STARTED; rows are created
--                          lazily on first "prepare". Carries the JPA @Version
--                          optimistic-lock column (Req 2.8) and points at its
--                          current snapshot.
--   * filing_snapshots   - immutable, append-only versioned copies of the exact
--                          figures filed for a period (full GSTR-1 sections or
--                          the GSTR-3B output/ITC/net map) as JSON.
--   * return_amendments  - routed / pending / manual-review post-filing
--                          corrections, one per (target period, original doc).
--
-- V55 (ledger_seed) is the previous highest version; never edit an applied
-- migration. All monetary columns are DECIMAL(15,2); JSON payloads are LONGTEXT;
-- timestamps are DATETIME. Engine / charset conventions match V1/V54
-- (InnoDB, utf8mb4). No change to orders, line_items, or the ledger tables.
--
-- Requirements: 10.3, 10.4
-- =============================================================================

-- ----------------------------------------------------------- return_filings ---
-- Filing-status lifecycle row for a Return_Period (period_year + period_month
-- 1-12) and Return_Type (GSTR1 | GSTR3B). status is one of NOT_STARTED (the
-- implicit state when no row exists), PREPARED, or FILED. ack_reference holds
-- the optional 1-50 char portal acknowledgement. filed_/reopened_ capture the
-- actor + Asia/Kolkata timestamp. current_snapshot_id points at the current
-- filing_snapshots row (FK added after that table exists, below). version is the
-- JPA @Version optimistic-lock column so concurrent file/re-file/reopen are
-- deterministic (Req 2.8). Unique per (year, month, return_type).
CREATE TABLE return_filings (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    period_year         INT          NOT NULL,
    period_month        INT          NOT NULL,
    return_type         VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'NOT_STARTED',
    ack_reference       VARCHAR(50)  NULL,
    filed_by            VARCHAR(100) NULL,
    filed_at            DATETIME     NULL,
    reopened_by         VARCHAR(100) NULL,
    reopened_at         DATETIME     NULL,
    current_snapshot_id BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_return_filings PRIMARY KEY (id),
    CONSTRAINT uq_return_filings_period_type UNIQUE (period_year, period_month, return_type),
    CONSTRAINT ck_return_filings_month CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_return_filings_type CHECK (return_type IN ('GSTR1','GSTR3B')),
    CONSTRAINT ck_return_filings_status CHECK (status IN ('NOT_STARTED','PREPARED','FILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_return_filings_period ON return_filings (period_year, period_month);

-- --------------------------------------------------------- filing_snapshots ---
-- Immutable, append-only copy of the exact figures filed for a period. payload
-- is the full GSTR-1 portal sections (b2b, b2cl, b2cs, cdnr, cdnur, hsn, docs)
-- for GSTR1, or the section-mapped output tax + ITC + net payable for GSTR3B
-- (Reqs 5.2, 5.3), stored as JSON in a LONGTEXT column. version is monotonic
-- from 1 per filing; the newest version is the current snapshot (Req 5.6).
-- There is no update/delete path - snapshots are never modified (Req 5.5).
CREATE TABLE filing_snapshots (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    return_filing_id BIGINT       NOT NULL,
    return_type      VARCHAR(16)  NOT NULL,
    period_year      INT          NOT NULL,
    period_month     INT          NOT NULL,
    version          INT          NOT NULL,
    payload_json     LONGTEXT     NOT NULL,
    filed_by         VARCHAR(100) NULL,
    filed_at         DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_filing_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_filing_snapshots_filing_version UNIQUE (return_filing_id, version),
    CONSTRAINT fk_filing_snapshots_filing FOREIGN KEY (return_filing_id) REFERENCES return_filings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_filing_snapshots_period_type ON filing_snapshots (period_year, period_month, return_type);

-- Wire return_filings.current_snapshot_id to the now-existing filing_snapshots
-- (the two tables reference each other; the FK is added here to break the cycle).
ALTER TABLE return_filings
    ADD CONSTRAINT fk_return_filings_current_snapshot
        FOREIGN KEY (current_snapshot_id) REFERENCES filing_snapshots (id);

-- -------------------------------------------------------- return_amendments ---
-- A post-filing correction detected against a Filed_Period's GSTR-1 snapshot,
-- routed the GST way. amendment_table (B2BA | B2CSA | CDNRA) is NULL while the
-- correction is PENDING or held for MANUAL_REVIEW. original_period_* + the
-- original document reference identify the Filed_Period entry being corrected;
-- target_period_* (nullable while PENDING) is the earliest non-FILED period the
-- amendment lands in (Reqs 3.3, 3.6). The originally filed value and the latest
-- corrected value are kept as JSON (Reqs 3.4, 3.8). status is one of PENDING,
-- ROUTED, or MANUAL_REVIEW. Unique per (target period, original document) so
-- repeated corrections for the same document consolidate (Req 3.8); note MySQL
-- treats NULL target-period keys as distinct, so multiple PENDING rows coexist.
CREATE TABLE return_amendments (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    amendment_table       VARCHAR(16)  NULL,
    original_period_year  INT          NOT NULL,
    original_period_month INT          NOT NULL,
    original_document_ref VARCHAR(100) NOT NULL,
    target_period_year    INT          NULL,
    target_period_month   INT          NULL,
    original_value_json   LONGTEXT     NULL,
    corrected_value_json  LONGTEXT     NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_return_amendments PRIMARY KEY (id),
    CONSTRAINT uq_return_amendments_target_doc UNIQUE (target_period_year, target_period_month, original_document_ref),
    CONSTRAINT ck_return_amendments_orig_month CHECK (original_period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_return_amendments_target_month CHECK (target_period_month IS NULL OR target_period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_return_amendments_table CHECK (amendment_table IS NULL OR amendment_table IN ('B2BA','B2CSA','CDNRA')),
    CONSTRAINT ck_return_amendments_status CHECK (status IN ('PENDING','ROUTED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_return_amendments_status ON return_amendments (status);
CREATE INDEX ix_return_amendments_original_period ON return_amendments (original_period_year, original_period_month);

-- ---------------------------------------------------------------- app_settings ---
-- Two new nullable configuration columns (additive, no backfill - every existing
-- app_settings row stays valid and unmodified, Req 10.4):
--   * gst_reminder_window_days      - filing-calendar reminder window; resolved
--                                     to [1,30] with a default of 7 when NULL
--                                     or out of range (Req 4.6).
--   * gst_reconciliation_tolerance  - reconciliation tolerance in rupees;
--                                     resolved to [0.00, 9999.99] with a default
--                                     of 1.00 when NULL or out of range
--                                     (Reqs 7.4, 9.1). DECIMAL(6,2) covers the
--                                     0.00-9999.99 range exactly.
ALTER TABLE app_settings
    ADD COLUMN gst_reminder_window_days INT NULL,
    ADD COLUMN gst_reconciliation_tolerance DECIMAL(6,2) NULL;
