-- =============================================================================
-- Shifa Herbal Remedies OMS - catalog & discovery (V3)
-- Adds product categories/collections, stock/inventory awareness, and a
-- "featured" flag to support advanced filtering, sorting, related products and
-- featured collections on the storefront. Engine/charset/money conventions
-- match V1/V2 (InnoDB, utf8mb4). All changes are additive and backward
-- compatible: existing products default to no category, zero stock, inventory
-- tracking OFF (so they always read as in-stock) and not featured.
-- =============================================================================

-- ------------------------------------------------------------- categories ----
-- Product categories / collections. `slug` is a URL-friendly identifier used by
-- storefront category landing pages (/shop?category=slug). `active` allows a
-- soft-deactivate so a category can be hidden without deleting it or orphaning
-- its products.
CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(140) NOT NULL,
    description VARCHAR(500) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT uq_categories_slug UNIQUE (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_categories_active_sort ON categories (active, sort_order);

-- --------------------------------------------- products: catalog columns -----
-- category_id     : optional FK to categories (a product may be uncategorised).
-- stock_quantity  : on-hand units; only meaningful when track_inventory is TRUE.
-- track_inventory : when FALSE the product opts out of stock tracking and always
--                   reads as IN_STOCK (default preserves current behaviour).
-- featured        : marks a product for the storefront "featured collection".
ALTER TABLE products
    ADD COLUMN category_id     BIGINT  NULL     AFTER visibility,
    ADD COLUMN stock_quantity  INT     NOT NULL DEFAULT 0     AFTER category_id,
    ADD COLUMN track_inventory BOOLEAN NOT NULL DEFAULT FALSE AFTER stock_quantity,
    ADD COLUMN featured        BOOLEAN NOT NULL DEFAULT FALSE AFTER track_inventory;

ALTER TABLE products
    ADD CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id);

CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_featured ON products (featured);

-- ------------------------------------------------------- seed categories ------
-- A sensible starter taxonomy for the herbal/ayurvedic catalog. Idempotent via
-- INSERT ... ON DUPLICATE KEY UPDATE on the unique slug so a re-run is a no-op.
INSERT INTO categories (name, slug, description, sort_order, active) VALUES
    ('Immunity',      'immunity',      'Kadha, Giloy, Chyawanprash and daily immunity boosters.', 10, TRUE),
    ('Digestion',     'digestion',     'Gentle support for digestion, gut health and metabolism.', 20, TRUE),
    ('Hair & Skin',   'hair-and-skin', 'Oils, gels and cleansers for healthy hair and glowing skin.', 30, TRUE),
    ('Juices',        'juices',        'Cold-pressed herbal juices for everyday wellness.', 40, TRUE),
    ('Churna',        'churna',        'Traditional powdered formulations and single-herb churnas.', 50, TRUE),
    ('Personal Care', 'personal-care', 'Daily personal care essentials, naturally formulated.', 60, TRUE)
ON DUPLICATE KEY UPDATE
    name        = VALUES(name),
    description = VALUES(description),
    sort_order  = VALUES(sort_order),
    active      = VALUES(active);
