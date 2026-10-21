-- Bank-account master, salesperson payment metadata, and Shopify settlement destination.
-- Existing app_settings bank_* columns remain for invoice backward compatibility.

CREATE TABLE bank_accounts (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    display_name          VARCHAR(100)  NOT NULL,
    account_type          VARCHAR(30)   NOT NULL,
    bank_name             VARCHAR(120)  NULL,
    account_holder_name   VARCHAR(120)  NULL,
    account_number        VARCHAR(40)   NULL,
    ifsc                  VARCHAR(20)   NULL,
    branch                VARCHAR(120)  NULL,
    upi_id                VARCHAR(120)  NULL,
    ledger_account_id     BIGINT        NULL,
    active                BOOLEAN       NOT NULL DEFAULT TRUE,
    default_account      BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order            INT           NOT NULL DEFAULT 0,
    internal_note         VARCHAR(500)  NULL,
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_bank_accounts PRIMARY KEY (id),
    CONSTRAINT uq_bank_accounts_display_name UNIQUE (display_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_bank_accounts_active_type
    ON bank_accounts (active, account_type, sort_order, id);

ALTER TABLE app_settings
    ADD COLUMN shopify_settlement_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN shopify_settlement_account_id BIGINT NULL,
    ADD CONSTRAINT fk_app_settings_shopify_settlement_account
        FOREIGN KEY (shopify_settlement_account_id) REFERENCES bank_accounts (id);

ALTER TABLE payments
    ADD COLUMN payment_method VARCHAR(30) NULL,
    ADD COLUMN bank_account_id BIGINT NULL,
    ADD COLUMN bank_account_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN bank_account_masked_snapshot VARCHAR(80) NULL,
    ADD COLUMN utr VARCHAR(80) NULL,
    ADD CONSTRAINT fk_payments_bank_account
        FOREIGN KEY (bank_account_id) REFERENCES bank_accounts (id);

CREATE INDEX ix_payments_utr ON payments (utr);
CREATE INDEX ix_payments_bank_account ON payments (bank_account_id);
