-- Accounting-aware ADMIN punched-date corrections.
-- Posted vouchers remain immutable. This table links the original voucher to
-- its dated reversal and replacement voucher for an order-date correction.

CREATE TABLE order_created_at_ledger_corrections (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    order_created_at_correction_id BIGINT   NOT NULL,
    source_type                VARCHAR(30)  NOT NULL,
    source_id                  BIGINT       NOT NULL,
    original_voucher_id        BIGINT       NOT NULL,
    reversal_voucher_id        BIGINT       NULL,
    replacement_voucher_id     BIGINT       NULL,
    status                     VARCHAR(20)  NOT NULL DEFAULT 'STARTED',
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at               DATETIME     NULL,
    CONSTRAINT pk_order_created_at_ledger_corrections PRIMARY KEY (id),
    CONSTRAINT fk_order_created_at_ledger_correction
        FOREIGN KEY (order_created_at_correction_id) REFERENCES order_created_at_corrections (id),
    CONSTRAINT fk_order_created_at_ledger_original
        FOREIGN KEY (original_voucher_id) REFERENCES vouchers (id),
    CONSTRAINT fk_order_created_at_ledger_reversal
        FOREIGN KEY (reversal_voucher_id) REFERENCES vouchers (id),
    CONSTRAINT fk_order_created_at_ledger_replacement
        FOREIGN KEY (replacement_voucher_id) REFERENCES vouchers (id),
    CONSTRAINT uq_order_created_at_ledger_source
        UNIQUE (order_created_at_correction_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_order_created_at_ledger_correction_order
    ON order_created_at_ledger_corrections (order_created_at_correction_id, id);
