-- =============================================================================
-- V66 — Categorized order-rejection reason (rejection-status feature).
--
-- Two things drove this:
--   1. Admin rejections previously stored only a free-text `rejection_reason`.
--      This adds a CATEGORY (`reject_reason`) so a salesperson sees a concrete
--      reason (Rate Issue / Address-Pincode Issue / Payment Issue / Other), with
--      the existing free-text column kept as the accompanying note.
--   2. A NEW terminal order status `PAYMENT_REJECTED` (added to the OrderStatus
--      enum in code) makes a payment-panel rejection a VISIBLE order status,
--      distinct from an admin REJECTED. No schema change is needed for the status
--      itself — `orders.order_status` is a VARCHAR that stores the enum name — so
--      this migration only adds the reason category column.
--
-- `reject_reason` mirrors `rto_reason` (V61): VARCHAR(30), nullable, stores the
-- RejectReason enum name via @Enumerated(STRING). Additive/nullable — safe on
-- existing data (historical REJECTED orders keep their free-text reason and a
-- NULL category).
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN reject_reason VARCHAR(30) NULL AFTER rejection_reason;
