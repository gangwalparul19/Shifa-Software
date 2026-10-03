-- =============================================================================
-- V68 — Shopify order import idempotency key.
--
-- The public storefront runs on Shopify. A Shopify `orders/create` webhook
-- (POST /api/webhooks/shopify/orders) mirrors each Shopify order into this OMS so
-- the fulfilment team sees it here, tagged as a Shopify order (orders.source =
-- 'SHOPIFY', a new enum value that needs no schema change on the STRING column).
--
-- `shopify_order_id` records the originating Shopify order id for such an imported
-- order. It is UNIQUE so a webhook retry / redelivery of the same Shopify order is
-- recognised and never creates a duplicate. It is NULL for every non-Shopify order
-- (salesperson / storefront / seeded), so this is fully additive and safe on
-- existing data — a unique index permits many NULLs in MySQL.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN shopify_order_id VARCHAR(64) NULL AFTER source;

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_shopify_order_id UNIQUE (shopify_order_id);
