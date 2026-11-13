-- Admin payment corrections (V112)
--
-- Keeps a durable before/after record whenever an administrator corrects the
-- upfront payment amount or receiving metadata after order entry. The current
-- payment snapshot remains on orders/payments for existing readers; this table
-- is also the source document for any double-entry delta posted to the ledger.

CREATE TABLE payment_corrections (
    id                                  BIGINT        NOT NULL AUTO_INCREMENT,
    order_id                            BIGINT        NOT NULL,
    previous_amount_received            DECIMAL(12,2) NOT NULL,
    corrected_amount_received           DECIMAL(12,2) NOT NULL,
    amount_delta                        DECIMAL(12,2) NOT NULL,
    previous_payment_method             VARCHAR(30)   NULL,
    corrected_payment_method            VARCHAR(30)   NULL,
    previous_bank_account_id            BIGINT        NULL,
    corrected_bank_account_id           BIGINT        NULL,
    previous_bank_account_name_snapshot VARCHAR(100)  NULL,
    corrected_bank_account_name_snapshot VARCHAR(100) NULL,
    previous_bank_account_masked_snapshot VARCHAR(80) NULL,
    corrected_bank_account_masked_snapshot VARCHAR(80) NULL,
    previous_utr                        VARCHAR(80)   NULL,
    corrected_utr                       VARCHAR(80)   NULL,
    reason                              VARCHAR(500)  NOT NULL,
    changed_by                          BIGINT        NULL,
    changed_by_username                 VARCHAR(100)  NULL,
    changed_at                          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_payment_corrections PRIMARY KEY (id),
    CONSTRAINT fk_payment_corrections_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_payment_corrections_order ON payment_corrections (order_id, changed_at, id);
CREATE INDEX ix_payment_corrections_changed ON payment_corrections (changed_at, id);
