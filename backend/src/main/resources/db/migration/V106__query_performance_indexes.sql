-- =============================================================================
-- V106 - Query-performance indexes
-- -----------------------------------------------------------------------------
-- Additive and idempotent in effect: plain CREATE INDEX statements on existing
-- columns, no data change. They back the hottest filters that today fall back to
-- a filesort or a wide scan as the orders / payments / status_history tables grow:
--
--  * orders (created_by, created_at)
--      The per-salesperson / per-team order lists, My Day and Team Performance all
--      run `WHERE created_by = ? [AND created_at in window] ORDER BY created_at DESC`.
--      The single-column ix_orders_created_by forces a sort; the composite serves
--      the filter, the window and the ordering from one index.
--  * orders (customer_mobile, created_at)
--      Same-day duplicate check, last-order prefill, Customer 360 history and the
--      public tracking-by-mobile lookup: `WHERE customer_mobile = ? ORDER BY created_at DESC`.
--  * orders (payment_verification_status, created_at)
--      The payment-verification queue and the exception centre read
--      `WHERE payment_verification_status = 'PENDING' ORDER BY created_at DESC`.
--      The single-column V42 index is low-selectivity and still needs a sort.
--  * payments (captured_at, bank_account_id)
--      Settlement KPIs and the assistant's "received in bank" figures filter payments by
--      capture window and group by receiving account; no index covered captured_at.
--  * status_history (to_status, changed_at)
--      "Orders that moved into stage X during a period" (history-based stage queries).
--
-- The existing single-column indexes are left in place: they remain useful for
-- other predicates and dropping an index is not needed to gain the benefit here.
-- =============================================================================

CREATE INDEX ix_orders_created_by_created_at ON orders (created_by, created_at);
CREATE INDEX ix_orders_customer_mobile_created_at ON orders (customer_mobile, created_at);
CREATE INDEX ix_orders_payment_verification_created ON orders (payment_verification_status, created_at);
CREATE INDEX ix_payments_captured_account ON payments (captured_at, bank_account_id);
CREATE INDEX ix_status_history_to_changed ON status_history (to_status, changed_at);
