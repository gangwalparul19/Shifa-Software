-- =============================================================================
-- Shifa Herbal Remedies OMS - coupons & discount codes (V6)
-- Adds a `coupons` table describing redeemable discount codes (PERCENT / FLAT /
-- FREE_SHIPPING) with applicability rules (active flag, validity window, minimum
-- cart amount, total + per-customer usage limits) and, on the `orders` table,
-- two additive columns recording the coupon applied at checkout and the money
-- discount granted.
--
-- Engine/charset/money conventions match V1-V5 (InnoDB, utf8mb4, DECIMAL(12,2)).
-- All changes are additive and backward compatible: the table is new and the
-- two new `orders` columns are nullable / defaulted so existing rows and guest
-- checkout continue to work unchanged.
--
--   * code               : the redeemable code, stored UPPER-cased, unique.
--   * type               : PERCENT | FLAT | FREE_SHIPPING (enforced by CHECK).
--   * value              : percent for PERCENT, flat amount for FLAT, 0 for
--                          FREE_SHIPPING.
--   * min_cart_amount    : minimum cart subtotal required to apply (NULL = none).
--   * max_discount_amount: cap on the computed discount for PERCENT (NULL = none).
--   * active             : master on/off toggle (deactivate instead of delete).
--   * starts_at / ends_at: optional validity window (NULL = open-ended).
--   * usage_limit        : total redemptions allowed across all customers
--                          (NULL = unlimited); used_count tracks redemptions.
--   * per_customer_limit : redemptions allowed per customer mobile (NULL = no
--                          per-customer cap).
-- =============================================================================

CREATE TABLE coupons (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code                VARCHAR(40)  NOT NULL,
    description         VARCHAR(255) NULL,
    type                VARCHAR(16)  NOT NULL,
    value               DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    min_cart_amount     DECIMAL(12,2) NULL,
    max_discount_amount DECIMAL(12,2) NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    starts_at           DATETIME     NULL,
    ends_at             DATETIME     NULL,
    usage_limit         INT          NULL,
    used_count          INT          NOT NULL DEFAULT 0,
    per_customer_limit  INT          NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_coupons PRIMARY KEY (id),
    CONSTRAINT uq_coupons_code UNIQUE (code),
    CONSTRAINT ck_coupons_type CHECK (type IN ('PERCENT', 'FLAT', 'FREE_SHIPPING')),
    CONSTRAINT ck_coupons_value CHECK (value >= 0),
    CONSTRAINT ck_coupons_used_count CHECK (used_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Storefront validation / redemption looks a coupon up by its (upper-cased) code.
CREATE INDEX ix_coupons_code_active ON coupons (code, active);

-- ------------------------------------------------- orders: coupon + discount --
-- coupon_code     : the coupon applied to this order (UPPER-cased snapshot), or
--                   NULL when no coupon was used. Kept as a snapshot (not an FK)
--                   so historical orders survive coupon deletion/rename and so
--                   per-customer usage can be counted by (coupon_code, mobile).
-- discount_amount : the money discount granted by that coupon (0.00 when none).
--                   total_amount stores the NET payable (subtotal - discount) so
--                   COD/remaining already reflect the discount.
ALTER TABLE orders
    ADD COLUMN coupon_code     VARCHAR(40)   NULL          AFTER customer_outstanding,
    ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER coupon_code;

-- Per-customer usage counting for per_customer_limit enforcement.
CREATE INDEX ix_orders_coupon_code ON orders (coupon_code);
