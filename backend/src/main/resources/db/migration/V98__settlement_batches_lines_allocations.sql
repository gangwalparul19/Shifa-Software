-- Operational settlement import ledger. This records imported statements and
-- allocations without replacing the existing receivable/remittance workflow.
CREATE TABLE settlement_batches (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    source_channel        VARCHAR(30)   NOT NULL,
    external_reference    VARCHAR(120)  NULL,
    statement_date        DATE          NULL,
    destination_account_id BIGINT       NULL,
    original_filename     VARCHAR(255)  NULL,
    file_hash             VARCHAR(64)   NOT NULL,
    status                VARCHAR(30)   NOT NULL,
    line_count            INT           NOT NULL DEFAULT 0,
    total_amount          DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    allocated_amount      DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    suspense_amount       DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    note                  VARCHAR(500)  NULL,
    created_by            BIGINT        NULL,
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_at             DATETIME      NULL,
    CONSTRAINT pk_settlement_batches PRIMARY KEY (id),
    CONSTRAINT uq_settlement_batches_file_hash UNIQUE (file_hash),
    CONSTRAINT uq_settlement_batches_source_reference UNIQUE (source_channel, external_reference),
    CONSTRAINT fk_settlement_batches_account FOREIGN KEY (destination_account_id) REFERENCES bank_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_settlement_batches_status_date
    ON settlement_batches (status, statement_date, id);

CREATE TABLE settlement_lines (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    batch_id              BIGINT        NOT NULL,
    source_row_number     INT           NOT NULL,
    order_code            VARCHAR(40)   NULL,
    shopify_order_id      VARCHAR(64)   NULL,
    awb                   VARCHAR(80)   NULL,
    utr                   VARCHAR(80)   NULL,
    transaction_date      DATE          NULL,
    amount                DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    expected_amount       DECIMAL(14,2) NULL,
    variance              DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    allocated_amount      DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    suspense_amount       DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    match_status          VARCHAR(30)   NOT NULL,
    match_method          VARCHAR(40)   NULL,
    resolved_order_id     BIGINT        NULL,
    resolved_payment_id   BIGINT        NULL,
    resolved_receivable_id BIGINT       NULL,
    message               VARCHAR(500)  NULL,
    resolved_by           BIGINT        NULL,
    resolved_at           DATETIME      NULL,
    CONSTRAINT pk_settlement_lines PRIMARY KEY (id),
    CONSTRAINT uq_settlement_lines_batch_row UNIQUE (batch_id, source_row_number),
    CONSTRAINT fk_settlement_lines_batch FOREIGN KEY (batch_id) REFERENCES settlement_batches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_settlement_lines_status ON settlement_lines (match_status, id);
CREATE INDEX ix_settlement_lines_order ON settlement_lines (resolved_order_id);
CREATE INDEX ix_settlement_lines_payment ON settlement_lines (resolved_payment_id);
CREATE INDEX ix_settlement_lines_receivable ON settlement_lines (resolved_receivable_id);

CREATE TABLE settlement_allocations (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    settlement_line_id    BIGINT        NOT NULL,
    target_type           VARCHAR(30)   NOT NULL,
    order_id              BIGINT        NULL,
    payment_id            BIGINT        NULL,
    receivable_id         BIGINT        NULL,
    amount                DECIMAL(14,2) NOT NULL,
    allocated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    allocated_by          BIGINT        NULL,
    CONSTRAINT pk_settlement_allocations PRIMARY KEY (id),
    CONSTRAINT uq_settlement_allocations_line_target UNIQUE (settlement_line_id, target_type, order_id, payment_id, receivable_id),
    CONSTRAINT fk_settlement_allocations_line FOREIGN KEY (settlement_line_id) REFERENCES settlement_lines (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_settlement_allocations_order ON settlement_allocations (order_id);
CREATE INDEX ix_settlement_allocations_payment ON settlement_allocations (payment_id);
CREATE INDEX ix_settlement_allocations_receivable ON settlement_allocations (receivable_id);
