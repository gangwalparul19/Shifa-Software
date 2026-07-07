-- =============================================================================
-- Shifa Herbal Remedies OMS - customer accounts (V4)
-- Adds customer self-service account features on top of the existing auth/user
-- model: optional customer identity fields on `users`, a `customer_addresses`
-- book scoped per user, and a nullable `customer_user_id` link on `orders` so a
-- logged-in customer's checkout can be associated to their account for order
-- history. Engine/charset/money conventions match V1/V2/V3 (InnoDB, utf8mb4).
--
-- All changes are additive and backward compatible:
--   * existing staff users keep NULL email/mobile (nullable columns);
--   * existing/guest orders keep NULL customer_user_id (guest checkout still
--     works unchanged);
--   * the pre-existing `wishlist_items` table (V1) is reused as-is for the
--     persisted save-for-later feature (customer_id = users.id).
-- =============================================================================

-- --------------------------------------------- users: customer identity ------
-- email  : optional contact / alternate login hint for storefront customers.
-- mobile : optional 10-digit mobile captured at registration; lets order history
--          also match historical guest orders placed with the same number.
-- Both nullable so existing staff rows (ADMIN/ACCOUNTANT/...) are unaffected.
ALTER TABLE users
    ADD COLUMN email  VARCHAR(150) NULL AFTER full_name,
    ADD COLUMN mobile VARCHAR(10)  NULL AFTER email;

-- An index on mobile speeds up the "orders for my mobile" history lookup and
-- future customer CRM views. Not unique: staff rows may share NULL and the same
-- number could in theory be reused, so uniqueness is enforced only on username.
CREATE INDEX ix_users_mobile ON users (mobile);

-- ------------------------------------------------- customer_addresses ---------
-- A saved address book entry owned by a customer (users.id). `is_default` marks
-- the address prefilled at checkout; the application guarantees at most one
-- default per user by clearing the others when one is set.
CREATE TABLE customer_addresses (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    label        VARCHAR(60)  NULL,
    full_name    VARCHAR(100) NOT NULL,
    mobile       VARCHAR(10)  NOT NULL,
    address_line VARCHAR(250) NOT NULL,
    city         VARCHAR(100) NOT NULL,
    state        VARCHAR(100) NOT NULL,
    postal_code  VARCHAR(6)   NOT NULL,
    is_default   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_customer_addresses PRIMARY KEY (id),
    CONSTRAINT fk_customer_addresses_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_customer_addresses_user ON customer_addresses (user_id);

-- --------------------------------------------- orders: customer link ----------
-- customer_user_id: the registered customer who placed a storefront order while
-- logged in (NULL for salesperson orders and anonymous/guest checkout). Order
-- history returns orders where customer_user_id = me OR customer_mobile = my
-- mobile, so a customer sees both account-linked and historical guest orders.
ALTER TABLE orders
    ADD COLUMN customer_user_id BIGINT NULL AFTER created_by;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_customer_user FOREIGN KEY (customer_user_id) REFERENCES users (id);

CREATE INDEX ix_orders_customer_user ON orders (customer_user_id);
