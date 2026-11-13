-- ADMIN order punched-date corrections (V113)
--
-- Keeps an immutable before/after record whenever an ADMIN corrects an order's
-- created_at date before downstream accounting/fulfilment artifacts exist.

CREATE TABLE order_created_at_corrections (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_id              BIGINT       NOT NULL,
    previous_created_at   DATETIME     NOT NULL,
    corrected_created_at  DATETIME     NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    changed_by            BIGINT       NULL,
    changed_by_username   VARCHAR(100) NULL,
    changed_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_order_created_at_corrections PRIMARY KEY (id),
    CONSTRAINT fk_order_created_at_corrections_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_order_created_at_corrections_order
    ON order_created_at_corrections (order_id, changed_at, id);
CREATE INDEX ix_order_created_at_corrections_changed
    ON order_created_at_corrections (changed_at, id);
