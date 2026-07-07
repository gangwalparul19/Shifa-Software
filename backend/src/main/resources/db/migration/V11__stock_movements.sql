-- =============================================================================
-- Shifa Herbal Remedies OMS - inventory / stock movements (V11)
-- Adds a `stock_movements` ledger (one row per restock / adjustment / sale /
-- return) and an optional per-product `low_stock_threshold` override. Engine /
-- charset / money conventions match V1 (InnoDB, utf8mb4). Additive only.
-- =============================================================================

-- ------------------------------------------------------ stock_movements -----
-- Append-only ledger of stock changes. `delta` is signed (+restock/+return,
-- -sale/-adjustment); `balance_after` snapshots the product's on-hand quantity
-- immediately after the movement so history is auditable without replay.
CREATE TABLE stock_movements (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    product_id     BIGINT       NOT NULL,
    delta          INT          NOT NULL,
    movement_type  VARCHAR(20)  NOT NULL,
    reason         VARCHAR(255) NULL,
    balance_after  INT          NOT NULL,
    created_by     BIGINT       NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_stock_movements PRIMARY KEY (id),
    CONSTRAINT fk_stock_movements_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_stock_movements_type CHECK (movement_type IN ('RESTOCK', 'ADJUSTMENT', 'SALE', 'RETURN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Recent-movements-per-product lookups (admin inventory drill-down).
CREATE INDEX ix_stock_movements_product ON stock_movements (product_id, created_at);

-- ----------------------------------------- products.low_stock_threshold -----
-- Optional per-product low-stock threshold. NULL means "use the settings-level
-- default (app_settings.low_stock_threshold)".
ALTER TABLE products ADD COLUMN low_stock_threshold INT NULL AFTER track_inventory;
