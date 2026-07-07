-- =============================================================================
-- Shifa Herbal Remedies OMS - Expenses & P&L (V20)
--
-- Feature C3 (package com.shifa.oms.finance): manually recorded business
-- expenses (rent, salaries, marketing, packaging, ...) that feed the profit &
-- loss report alongside order revenue and courier/claim costs derived from the
-- reconciliation receivables. Only the expenses themselves are persisted here;
-- the P&L report is computed on the fly and stores nothing.
--
-- created_by is a best-effort actor snapshot (nullable, no FK) matching the
-- audit convention. Engine / charset conventions match V1 (InnoDB, utf8mb4).
-- Additive only.
-- =============================================================================

CREATE TABLE expenses (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    category    VARCHAR(100)  NOT NULL,
    description VARCHAR(500)  NULL,
    amount      DECIMAL(12,2) NOT NULL,
    incurred_on DATE          NOT NULL,
    created_by  BIGINT        NULL,
    created_at  DATETIME      NOT NULL,
    CONSTRAINT pk_expenses PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The listing / P&L window filters and sums by the date the expense was incurred.
CREATE INDEX ix_expenses_incurred_on ON expenses (incurred_on);
