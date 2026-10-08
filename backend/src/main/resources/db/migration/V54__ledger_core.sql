-- =============================================================================
-- Shifa Herbal Remedies OMS - General Ledger core tables (V54)
--
-- Spec: general-ledger-accounting (Phase 1). Introduces the double-entry
-- General Ledger data model for the new, self-contained com.shifa.oms.ledger
-- module: a Tally-style Chart of Accounts, Indian financial years, opening
-- balances, immutable balanced vouchers with their lines, a per-type-per-FY
-- voucher-reference counter, and a per-source idempotency log for auto-posting.
--
--   * account_groups           - nature-classified, self-nesting account groups
--                                (ASSET/LIABILITY/INCOME/EXPENSE/EQUITY).
--   * ledger_accounts          - postable ledgers under a group; nature is
--                                DERIVED from the group (not stored). control_key
--                                is the natural key for seeded control ledgers.
--   * financial_years          - Indian FY (1 Apr - 31 Mar); a closed FY locks
--                                posting.
--   * opening_balances         - one opening balance per ledger per FY.
--   * vouchers                 - posted, structurally-immutable double-entry
--                                vouchers across the eight voucher types, with
--                                optional source-document key + reversal links.
--   * voucher_lines            - the Dr/Cr lines of a voucher (exactly one of
--                                debit/credit populated per line, enforced in the
--                                pure domain).
--   * ledger_voucher_sequences - gap-tolerant per-type-per-FY reference counter,
--                                mirroring invoice_sequence / purchase_order_sequence.
--   * ledger_source_postings   - per-source-document idempotency + trace record
--                                for decoupled auto-posting.
--
-- All monetary columns are DECIMAL(15,2). NEW TABLES ONLY - no existing table or
-- column is altered (Req 17.1, 17.2), so this is backward compatible with the
-- order/procurement/expense/GST/reporting modules. V53 is the previous highest
-- version; never edit an applied migration. Engine / charset conventions match
-- V1 (InnoDB, utf8mb4).
--
-- Requirements: 17.1, 17.2, 1.1, 1.2, 2.1, 3.1, 4.1, 5.8, 6.4
-- =============================================================================

-- ---------------------------------------------------------- account_groups ---
-- Nature-classified account groups; parent_group_id self-nests them into a
-- hierarchy. A child group's nature always equals its parent's (derived in the
-- service). system_generated marks the seeded default groups (V55).
CREATE TABLE account_groups (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(150) NOT NULL,
    nature           VARCHAR(16)  NOT NULL,
    parent_group_id  BIGINT       NULL,
    system_generated BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_account_groups PRIMARY KEY (id),
    CONSTRAINT uq_account_groups_parent_name UNIQUE (parent_group_id, name),
    CONSTRAINT fk_account_groups_parent FOREIGN KEY (parent_group_id) REFERENCES account_groups (id),
    CONSTRAINT ck_account_groups_nature CHECK (nature IN ('ASSET','LIABILITY','INCOME','EXPENSE','EQUITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_account_groups_parent ON account_groups (parent_group_id);
CREATE INDEX ix_account_groups_nature ON account_groups (nature);

-- ---------------------------------------------------------- ledger_accounts ---
-- Postable ledgers under an account group. Nature is NOT stored here - it is
-- derived from the owning group at read time. control_key is the ControlAccount
-- natural key for the seeded control ledgers (e.g. SALES, SUNDRY_DEBTORS) and is
-- unique so auto-posting can resolve a control role to a ledger id.
CREATE TABLE ledger_accounts (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(150) NOT NULL,
    account_group_id BIGINT       NOT NULL,
    control_key      VARCHAR(40)  NULL,
    system_generated BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_ledger_accounts PRIMARY KEY (id),
    CONSTRAINT uq_ledger_accounts_group_name UNIQUE (account_group_id, name),
    CONSTRAINT uq_ledger_accounts_control_key UNIQUE (control_key),
    CONSTRAINT fk_ledger_accounts_group FOREIGN KEY (account_group_id) REFERENCES account_groups (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_ledger_accounts_group ON ledger_accounts (account_group_id);

-- ---------------------------------------------------------- financial_years ---
-- Indian financial year (1 Apr - 31 Mar). closed locks the year against further
-- posting; closed_at / closed_by snapshot when and by whom it was closed.
CREATE TABLE financial_years (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    start_date DATE        NOT NULL,
    end_date   DATE        NOT NULL,
    label      VARCHAR(16) NOT NULL,
    closed     BOOLEAN     NOT NULL DEFAULT FALSE,
    closed_at  DATETIME    NULL,
    closed_by  VARCHAR(100) NULL,
    CONSTRAINT pk_financial_years PRIMARY KEY (id),
    CONSTRAINT uq_financial_years_start_date UNIQUE (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------- opening_balances ---
-- One opening balance per ledger account per financial year. amount is a
-- positive magnitude; side records whether it is a DEBIT or CREDIT opening.
CREATE TABLE opening_balances (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    ledger_account_id BIGINT        NOT NULL,
    financial_year_id BIGINT        NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    side              VARCHAR(8)    NOT NULL,
    CONSTRAINT pk_opening_balances PRIMARY KEY (id),
    CONSTRAINT uq_opening_balances_ledger_fy UNIQUE (ledger_account_id, financial_year_id),
    CONSTRAINT fk_opening_balances_ledger FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),
    CONSTRAINT fk_opening_balances_fy FOREIGN KEY (financial_year_id) REFERENCES financial_years (id),
    CONSTRAINT ck_opening_balances_side CHECK (side IN ('DEBIT','CREDIT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------- vouchers ---
-- Posted, structurally-immutable double-entry vouchers. voucher_reference is
-- unique per (type, financial year). source_type/source_id are the optional
-- source-document key for auto-posted vouchers and are unique together so a
-- source document posts at most one voucher (auto-posting idempotency).
-- reverses_voucher_id / reversed_by_voucher_id are the bidirectional reversal
-- links (Req 6.4). There is no update/delete path for a posted voucher.
CREATE TABLE vouchers (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    voucher_type           VARCHAR(16)   NOT NULL,
    voucher_date           DATE          NOT NULL,
    financial_year_id      BIGINT        NOT NULL,
    voucher_reference      VARCHAR(40)   NOT NULL,
    narration              VARCHAR(1000) NULL,
    posted                 BOOLEAN       NOT NULL DEFAULT TRUE,
    posted_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by              VARCHAR(100)  NULL,
    source_type            VARCHAR(20)   NULL,
    source_id              BIGINT        NULL,
    reverses_voucher_id    BIGINT        NULL,
    reversed_by_voucher_id BIGINT        NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_vouchers PRIMARY KEY (id),
    CONSTRAINT uq_vouchers_type_fy_reference UNIQUE (voucher_type, financial_year_id, voucher_reference),
    CONSTRAINT uq_vouchers_source UNIQUE (source_type, source_id),
    CONSTRAINT fk_vouchers_fy FOREIGN KEY (financial_year_id) REFERENCES financial_years (id),
    CONSTRAINT fk_vouchers_reverses FOREIGN KEY (reverses_voucher_id) REFERENCES vouchers (id),
    CONSTRAINT fk_vouchers_reversed_by FOREIGN KEY (reversed_by_voucher_id) REFERENCES vouchers (id),
    CONSTRAINT ck_vouchers_type CHECK (voucher_type IN
        ('JOURNAL','PAYMENT','RECEIPT','CONTRA','SALES','PURCHASE','DEBIT_NOTE','CREDIT_NOTE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_vouchers_date ON vouchers (voucher_date);
CREATE INDEX ix_vouchers_fy ON vouchers (financial_year_id);

-- ------------------------------------------------------------- voucher_lines ---
-- The Dr/Cr lines of a voucher. Exactly one of debit/credit is non-null and > 0
-- per line (enforced in the pure ledger.domain validator). line_order preserves
-- the entry order for display; line_narration is an optional per-line note.
CREATE TABLE voucher_lines (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    voucher_id        BIGINT        NOT NULL,
    ledger_account_id BIGINT        NOT NULL,
    debit             DECIMAL(15,2) NULL,
    credit            DECIMAL(15,2) NULL,
    line_order        INT           NOT NULL DEFAULT 0,
    line_narration    VARCHAR(500)  NULL,
    CONSTRAINT pk_voucher_lines PRIMARY KEY (id),
    CONSTRAINT fk_voucher_lines_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT fk_voucher_lines_ledger FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_voucher_lines_ledger ON voucher_lines (ledger_account_id);
CREATE INDEX ix_voucher_lines_voucher ON voucher_lines (voucher_id);

-- ------------------------------------------------- ledger_voucher_sequences ---
-- Gap-tolerant running counter per (voucher type, financial year), mirroring the
-- invoice_sequence / purchase_order_sequence pattern (V15/V19). Allocation bumps
-- next_value under a row lock so two vouchers of the same type/FY never share a
-- reference (Req 5.8). Rows are created on demand by the service.
CREATE TABLE ledger_voucher_sequences (
    voucher_type      VARCHAR(16) NOT NULL,
    financial_year_id BIGINT      NOT NULL,
    next_value        BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT pk_ledger_voucher_sequences PRIMARY KEY (voucher_type, financial_year_id),
    CONSTRAINT fk_ledger_voucher_sequences_fy FOREIGN KEY (financial_year_id) REFERENCES financial_years (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------- ledger_source_postings ---
-- One row per successfully auto-posted source document (source_type, source_id
-- -> voucher_id), giving a fast idempotency pre-check and a CA-visible trace from
-- a source document to its voucher. The authoritative idempotency guard remains
-- the unique (source_type, source_id) constraint on vouchers.
CREATE TABLE ledger_source_postings (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL,
    source_id   BIGINT      NOT NULL,
    voucher_id  BIGINT      NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ledger_source_postings PRIMARY KEY (id),
    CONSTRAINT uq_ledger_source_postings_source UNIQUE (source_type, source_id),
    CONSTRAINT fk_ledger_source_postings_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
