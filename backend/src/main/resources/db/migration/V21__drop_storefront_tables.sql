-- =============================================================================
-- Shifa Herbal Remedies OMS - drop storefront tables (V21, dashboard-only pivot)
-- The public storefront has been retired (the client now runs the store on
-- Shopify); only the admin dashboard + salesperson order entry remain. This
-- migration drops the storefront-only tables so a freshly-migrated database
-- (e.g. the new `shifa_dashboard`) ends up with a clean, dashboard-only schema.
--
-- SAFETY / SCOPE:
--   * Only standalone storefront tables are dropped. Each of these tables has a
--     FK *into* a kept table (users / products / orders) but nothing kept refers
--     *to* them, so dropping them cannot orphan or break any retained data.
--   * No kept JPA entity maps any of these tables, so Hibernate
--     `ddl-auto: validate` still passes after the drop.
--   * Columns that earlier storefront migrations ADDED to kept tables
--     (products.category_id/stock_quantity/track_inventory/featured,
--      users.email/mobile, orders.customer_user_id/coupon_code/discount_amount)
--     are intentionally LEFT IN PLACE: they are still mapped by retained
--     entities (Product, User, OrderEntity) and are harmless.
--   * The `categories` table is intentionally KEPT: admin category management
--     (/api/admin/categories) is retained for organising products.
--
-- Idempotent via DROP TABLE IF EXISTS. FK checks are toggled off for the drop so
-- table order is irrelevant and no residual constraint blocks a drop.
-- =============================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- Customer accounts & self-service (V4, V9, V10) --------------------------------
DROP TABLE IF EXISTS customer_cart_items;   -- persisted per-customer cart (V10)
DROP TABLE IF EXISTS wishlist_shares;       -- public wishlist share links (V9)
DROP TABLE IF EXISTS customer_addresses;    -- customer saved address book (V4)

-- Session cart & save-for-later (V1) -------------------------------------------
DROP TABLE IF EXISTS cart_items;            -- anonymous/session cart (V1)
DROP TABLE IF EXISTS wishlist_items;        -- save-for-later wishlist (V1)

-- Reviews, coupons, online payments (V5, V6, V7) -------------------------------
DROP TABLE IF EXISTS product_reviews;       -- customer reviews + moderation (V5)
DROP TABLE IF EXISTS coupons;               -- discount codes (V6)
DROP TABLE IF EXISTS payment_transactions;  -- online payment attempts (V7)

SET FOREIGN_KEY_CHECKS = 1;
