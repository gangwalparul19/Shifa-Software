-- Canonical payment UTRs and a safe database uniqueness boundary.
--
-- UTRs are evidence, so this migration never deletes or merges payment rows.
-- It records every historical canonical duplicate in the preflight table. When
-- there are no duplicate (receiving-account, canonical-UTR) groups, it installs
-- the unique index. If duplicates exist, it installs only a lookup index and
-- leaves the operator-visible preflight rows for review. After an operator has
-- resolved the historical duplicates without deleting financial evidence, the
-- following statement may be run during a maintenance window:
--   CREATE UNIQUE INDEX uq_payments_receiving_account_utr
--       ON payments (bank_account_id, utr_normalized);
-- The application remains protected by its friendly pre-check in either case;
-- the unique index is the authoritative race-safe boundary once present.

ALTER TABLE payments
    ADD COLUMN utr_normalized VARCHAR(80) NULL AFTER utr;

UPDATE payments
SET utr_normalized = NULLIF(
        UPPER(
            REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(utr,
                ' ', ''), CHAR(9), ''), CHAR(10), ''), CHAR(11), ''), CHAR(12), ''), CHAR(13), '')
        ),
        ''
    )
WHERE utr IS NOT NULL;

CREATE TABLE payment_utr_preflight_duplicates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bank_account_id BIGINT NOT NULL,
    utr_normalized VARCHAR(80) NOT NULL,
    payment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    duplicate_count INT NOT NULL,
    detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX ix_payment_utr_preflight_group (bank_account_id, utr_normalized),
    INDEX ix_payment_utr_preflight_payment (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO payment_utr_preflight_duplicates
        (bank_account_id, utr_normalized, payment_id, order_id, duplicate_count)
SELECT p.bank_account_id, p.utr_normalized, p.id, p.order_id, d.duplicate_count
FROM payments p
JOIN (
    SELECT bank_account_id, utr_normalized, COUNT(*) AS duplicate_count
    FROM payments
    WHERE bank_account_id IS NOT NULL
      AND utr_normalized IS NOT NULL
    GROUP BY bank_account_id, utr_normalized
    HAVING COUNT(*) > 1
) d ON d.bank_account_id = p.bank_account_id
   AND d.utr_normalized = p.utr_normalized
WHERE p.bank_account_id IS NOT NULL
  AND p.utr_normalized IS NOT NULL;

SET @payment_utr_duplicate_groups = (
    SELECT COUNT(*)
    FROM (
        SELECT bank_account_id, utr_normalized
        FROM payments
        WHERE bank_account_id IS NOT NULL
          AND utr_normalized IS NOT NULL
        GROUP BY bank_account_id, utr_normalized
        HAVING COUNT(*) > 1
    ) duplicate_groups
);

SET @payment_utr_index_sql = IF(
    @payment_utr_duplicate_groups = 0,
    'CREATE UNIQUE INDEX uq_payments_receiving_account_utr ON payments (bank_account_id, utr_normalized)',
    'CREATE INDEX ix_payments_receiving_account_utr_pending ON payments (bank_account_id, utr_normalized)'
);
PREPARE payment_utr_index_statement FROM @payment_utr_index_sql;
EXECUTE payment_utr_index_statement;
DEALLOCATE PREPARE payment_utr_index_statement;
