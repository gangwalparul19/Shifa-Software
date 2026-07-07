-- =============================================================================
-- Shifa Herbal Remedies OMS - product reviews & ratings (V5)
-- Adds a `product_reviews` table capturing customer-submitted ratings/reviews
-- with an admin moderation lifecycle (PENDING -> APPROVED | REJECTED). Only
-- APPROVED reviews are ever shown on the storefront; aggregates (average rating
-- + count) are computed from APPROVED rows only.
--
-- Engine/charset/money conventions match V1-V4 (InnoDB, utf8mb4). All changes
-- are additive and backward compatible: the table is new and no existing table
-- is modified.
--
--   * product_id : the reviewed product (FK -> products.id).
--   * user_id    : the authenticated customer who wrote it (FK -> users.id,
--                  nullable so a name-only review is representable, though the
--                  application requires a logged-in CUSTOMER to submit).
--   * author_name: display name snapshot (from the customer profile at submit).
--   * rating     : 1..5 stars, enforced by a CHECK constraint.
--   * verified   : whether the author actually purchased this product (computed
--                  from order history at submit time) - a "verified purchase"
--                  badge; it never blocks submission.
--   * status     : moderation state; defaults to PENDING (awaits moderation).
--   * moderated_* : who/when the review was approved or rejected.
-- =============================================================================

CREATE TABLE product_reviews (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    product_id   BIGINT        NOT NULL,
    user_id      BIGINT        NULL,
    author_name  VARCHAR(120)  NOT NULL,
    rating       TINYINT       NOT NULL,
    title        VARCHAR(150)  NULL,
    body         VARCHAR(2000) NULL,
    verified     BOOLEAN       NOT NULL DEFAULT FALSE,
    status       VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    moderated_at DATETIME      NULL,
    moderated_by BIGINT        NULL,
    CONSTRAINT pk_product_reviews PRIMARY KEY (id),
    CONSTRAINT fk_product_reviews_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_reviews_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_product_reviews_moderator FOREIGN KEY (moderated_by) REFERENCES users (id),
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_reviews_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Public product page: fetch APPROVED reviews for a product + aggregate fast.
CREATE INDEX ix_product_reviews_product_status ON product_reviews (product_id, status);

-- Admin moderation queue: list reviews by status (e.g. PENDING) efficiently.
CREATE INDEX ix_product_reviews_status ON product_reviews (status);

-- One review per customer per product: a customer's re-submission updates their
-- existing review (see ReviewService) rather than creating duplicates. NULL
-- user_id rows (defensive) are exempt because MySQL treats NULLs as distinct.
CREATE UNIQUE INDEX ux_product_reviews_user_product ON product_reviews (user_id, product_id);
