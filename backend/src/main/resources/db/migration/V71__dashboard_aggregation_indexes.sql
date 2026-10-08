-- =============================================================================
-- Shifa Herbal Remedies OMS - dashboard aggregation indexes (V71)
--
-- The role/channel dashboards aggregate the order book and the receivables
-- ledger. This migration adds the indexes that back those aggregations so the
-- DB can satisfy the windowed/filtered reads without a full table scan as the
-- data grows. Existing single-column indexes (from V1) are kept and complement
-- these:
--
--   * orders(order_status), orders(created_at), orders(created_by),
--     orders(customer_mobile)                         -> V1
--   * orders(payment_verification_status)             -> V42
--   * receivables(order_id), receivables(type)        -> V1
--   * receivables(created_at, id)                     -> V14
--
-- Added here:
--   1. orders(order_status, created_at) — composite for the "orders in stage X
--      within a date window" counts the admin/metrics dashboards compute, and
--      for status-filtered windowed revenue sums.
--   2. orders(source) — the channel dashboard splits the book by source
--      (PORTAL vs SHOPIFY); source was not previously indexed.
--   3. receivables(type, settled) — the accountant/metrics dashboards sum
--      receivable amounts grouped by (type, settled) to show outstanding vs
--      settled COD/claims; this composite backs that grouping directly.
--
-- Additive only (no drops); safe to run on existing data.
-- =============================================================================

CREATE INDEX ix_orders_status_created ON orders (order_status, created_at);
CREATE INDEX ix_orders_source ON orders (source);
CREATE INDEX ix_receivables_type_settled ON receivables (type, settled);
