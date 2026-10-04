-- =============================================================================
-- V78 — Return refund method (ENHANCEMENT 2.3).
--
-- Completes the returns/refund workflow: records HOW a refund was paid back to
-- the customer (cash / UPI / bank transfer / back to original payment / COD not
-- collected). Nullable + additive — existing refunded returns keep a null method
-- (reported as "Unspecified"). Powers the guided refund modal and the refund
-- ledger posting (SourceType.RETURN_REFUND).
-- =============================================================================
ALTER TABLE order_returns
    ADD COLUMN refund_method VARCHAR(20) NULL AFTER refund_amount;
