-- =============================================================================
-- Shifa Herbal Remedies OMS - Returns / Refunds / RTO workflow (V18)
--
-- "Operations depth" Feature 1: a first-class record of a customer return /
-- refund / RTO against an order. Today RTO is only an order lifecycle status
-- (no return record); this table gives the admin a proper workflow:
--   REQUESTED -> APPROVED -> REFUNDED, plus REJECTED (terminal).
--
-- refund_amount is nullable (only set once known); restocked records whether the
-- order's line items were returned to inventory at approval time. created_by is
-- a best-effort actor snapshot (nullable, no FK) matching the audit convention.
--
-- Engine / charset conventions match V1 (InnoDB, utf8mb4). Additive only.
-- =============================================================================

CREATE TABLE order_returns (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    order_id      BIGINT        NOT NULL,
    reason        VARCHAR(250)  NOT NULL,
    notes         TEXT          NULL,
    status        VARCHAR(20)   NOT NULL,
    refund_amount DECIMAL(12,2) NULL,
    restocked     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by    BIGINT        NULL,
    created_at    DATETIME      NOT NULL,
    updated_at    DATETIME      NULL,
    CONSTRAINT pk_order_returns PRIMARY KEY (id),
    CONSTRAINT fk_order_returns_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The default listing is filtered by status, newest-first.
CREATE INDEX ix_order_returns_status_created ON order_returns (status, created_at);

-- Fetch the return(s) for a given order (and enforce one-active-return checks).
CREATE INDEX ix_order_returns_order ON order_returns (order_id);
