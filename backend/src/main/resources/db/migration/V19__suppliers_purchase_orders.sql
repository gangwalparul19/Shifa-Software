-- =============================================================================
-- Shifa Herbal Remedies OMS - Suppliers & Purchase Orders (V19)
--
-- Feature C2 (package com.shifa.oms.procurement): a first-class procurement
-- workflow. Suppliers are the vendors we buy stock from; a purchase order (PO)
-- captures what we ordered from a supplier and at what cost. Receiving a PO
-- feeds inventory (a RESTOCK movement per received line via StockService).
--
--   PO lifecycle: DRAFT -> ORDERED -> (PARTIALLY_RECEIVED) -> RECEIVED,
--   plus CANCELLED (only from DRAFT/ORDERED).
--
-- po_number is a stable, human-readable sequence (PO-0001, ...) allocated from
-- the single-row purchase_order_sequence table under a row lock, mirroring the
-- invoice_sequence pattern (V15) so two POs never share a number.
--
-- created_by is a best-effort actor snapshot (nullable, no FK) matching the
-- audit convention. Engine / charset conventions match V1 (InnoDB, utf8mb4).
-- Additive only.
-- =============================================================================

CREATE TABLE suppliers (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(150) NOT NULL,
    contact_person VARCHAR(150) NULL,
    phone          VARCHAR(30)  NULL,
    email          VARCHAR(150) NULL,
    address        VARCHAR(500) NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME     NOT NULL,
    CONSTRAINT pk_suppliers PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The supplier picker lists active suppliers by name.
CREATE INDEX ix_suppliers_active_name ON suppliers (active, name);

CREATE TABLE purchase_orders (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    po_number    VARCHAR(30)   NOT NULL,
    supplier_id  BIGINT        NOT NULL,
    status       VARCHAR(30)   NOT NULL,
    notes        VARCHAR(1000) NULL,
    total_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_by   BIGINT        NULL,
    created_at   DATETIME      NOT NULL,
    received_at  DATETIME      NULL,
    CONSTRAINT pk_purchase_orders PRIMARY KEY (id),
    CONSTRAINT uq_purchase_orders_po_number UNIQUE (po_number),
    CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The default listing is filtered by status / supplier, newest-first.
CREATE INDEX ix_purchase_orders_status_created ON purchase_orders (status, created_at);
CREATE INDEX ix_purchase_orders_supplier ON purchase_orders (supplier_id);

CREATE TABLE purchase_order_items (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    purchase_order_id BIGINT        NOT NULL,
    product_id        BIGINT        NOT NULL,
    quantity          INT           NOT NULL,
    unit_cost         DECIMAL(12,2) NOT NULL,
    received_quantity INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_order_items PRIMARY KEY (id),
    CONSTRAINT fk_po_items_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id),
    CONSTRAINT fk_po_items_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Fetch the line items for a PO.
CREATE INDEX ix_po_items_po ON purchase_order_items (purchase_order_id);

-- Single-row running counter for PO numbers (PO-0001, ...), mirroring
-- invoice_sequence. Seeded to 1 so the first allocated PO is PO-0001.
CREATE TABLE purchase_order_sequence (
    id         BIGINT NOT NULL,
    next_value BIGINT NOT NULL,
    CONSTRAINT pk_purchase_order_sequence PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO purchase_order_sequence (id, next_value) VALUES (1, 1);
