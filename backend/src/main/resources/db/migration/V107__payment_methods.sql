-- Payment-method master. The methods offered at order entry used to be a hard-coded enum
-- (UPI_QR / BANK_TRANSFER / CASH); they are now rows an admin manages under Settings.
--
--   code                 immutable key stored on payments.payment_method (no FK on purpose:
--                        historical payments keep their code even if a method is later removed)
--   requires_account     the salesperson must name the account that received the money
--   linked_account_type  when set, the account is auto-selected from the active accounts of that
--                        purpose (e.g. SHOPIFY_SETTLEMENT for "Credit Card Link")
--   default_method       exactly one active method is pre-selected at order entry
--
-- Additive. payments.payment_method is already VARCHAR(30) (V97), so no change is needed there.

CREATE TABLE payment_methods (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    code                 VARCHAR(30)  NOT NULL,
    label                VARCHAR(60)  NOT NULL,
    requires_account     BOOLEAN      NOT NULL DEFAULT TRUE,
    linked_account_type  VARCHAR(30)  NULL,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    default_method       BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_payment_methods PRIMARY KEY (id),
    CONSTRAINT uq_payment_methods_code UNIQUE (code),
    CONSTRAINT uq_payment_methods_label UNIQUE (label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_payment_methods_active_sort ON payment_methods (active, sort_order, id);

INSERT INTO payment_methods
    (code, label, requires_account, linked_account_type, active, default_method, sort_order)
VALUES
    ('UPI_QR',           'UPI / QR',         TRUE,  NULL,                 TRUE, TRUE,  10),
    ('BANK_TRANSFER',    'Bank transfer',    TRUE,  NULL,                 TRUE, FALSE, 20),
    ('CASH',             'Cash',             FALSE, NULL,                 TRUE, FALSE, 30),
    ('CREDIT_CARD_LINK', 'Credit Card Link', TRUE,  'SHOPIFY_SETTLEMENT', TRUE, FALSE, 40);
