-- =============================================================================
-- Shifa Herbal Remedies OMS - persisted customer cart (V10)
-- Adds a `customer_cart_items` table backing "cart survives across devices": a
-- signed-in customer's cart is saved server-side, keyed by their users.id, so it
-- can be restored on any device after login (ROADMAP 1.2).
--
-- This is distinct from the V1 `cart_items` table, which is keyed by a
-- lightweight/session identity (no user FK). `customer_cart_items` is scoped to a
-- registered customer (customer_id -> users.id) so it is safe to enforce the FK.
--
-- Engine/charset conventions match V1-V9 (InnoDB, utf8mb4). The change is purely
-- additive: the table is brand new and nothing on existing tables is modified,
-- so existing cart / wishlist / checkout behaviour is untouched.
--
--   * customer_id : the owning customer (users.id).
--   * product_id  : the saved product (products.id).
--   * quantity    : the saved quantity for that product (1..999).
--   * updated_at  : when the row was last written (auto-maintained), so the most
--     recently touched cart can be identified.
--   * the (customer_id, product_id) UNIQUE key keeps at most one row per product
--     per customer, so a replace/upsert never produces duplicate cart lines.
-- =============================================================================

CREATE TABLE customer_cart_items (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    customer_id BIGINT   NOT NULL,
    product_id  BIGINT   NOT NULL,
    quantity    INT      NOT NULL,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_cart_items PRIMARY KEY (id),
    CONSTRAINT uq_customer_cart_items_customer_product UNIQUE (customer_id, product_id),
    CONSTRAINT fk_customer_cart_items_customer FOREIGN KEY (customer_id) REFERENCES users (id),
    CONSTRAINT fk_customer_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_customer_cart_items_quantity CHECK (quantity BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_customer_cart_items_customer ON customer_cart_items (customer_id);
