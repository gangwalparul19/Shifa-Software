-- =============================================================================
-- Shifa Herbal Remedies OMS - wishlist sharing (V9)
-- Adds a `wishlist_shares` table backing the public, read-only "share my
-- wishlist" feature: a signed-in customer generates an unguessable token that
-- anyone can use to view the customer's saved products (product summaries only,
-- never PII) and add them to their own cart. The owner can revoke/regenerate
-- the link, so at most one active token exists per customer (customer_id UNIQUE).
--
-- Engine/charset conventions match V1-V8 (InnoDB, utf8mb4). The change is purely
-- additive: the table is brand new and nothing on existing tables is modified,
-- so existing wishlist / account / checkout behaviour is untouched.
--
--   * customer_id : the owning customer (users.id); UNIQUE keeps one live token
--                   per customer (create-or-get is idempotent).
--   * token       : URL-safe unguessable share token (SecureRandom, base64url),
--                   UNIQUE so a token resolves to exactly one wishlist.
--   * created_at  : when the current token was generated.
-- =============================================================================

CREATE TABLE wishlist_shares (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id BIGINT      NOT NULL,
    token       VARCHAR(64) NOT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_wishlist_shares PRIMARY KEY (id),
    CONSTRAINT uq_wishlist_shares_customer UNIQUE (customer_id),
    CONSTRAINT uq_wishlist_shares_token UNIQUE (token),
    CONSTRAINT fk_wishlist_shares_customer FOREIGN KEY (customer_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
