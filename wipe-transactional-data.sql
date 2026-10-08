-- ============================================================================
-- Shifa OMS — wipe transactional data + reset AUTO_INCREMENT to 1
--
-- DESTRUCTIVE. This permanently deletes orders, payments, leads, vouchers,
-- receivables, the audit trail, and related detail rows. It cannot be undone.
--
-- BEFORE RUNNING:
--   1. Take a fresh backup:
--        mysqldump --no-tablespaces -u<user> -p <db> > backup-before-wipe.sql
--   2. Stop the backend (recommended) so no in-flight writes / outbox events
--      are re-created mid-wipe.
--
-- HOW TO RUN (from the DB host / a client that can reach it):
--   mysql -u<user> -p <db> < wipe-transactional-data.sql
-- ...or open the target DB (USE shifa_dashboard;) and run this file.
--
-- TRUNCATE empties each table AND resets its AUTO_INCREMENT to 1 in one step.
-- FOREIGN_KEY_CHECKS is disabled around the block so FK ordering can't block us
-- (it is re-enabled at the end).
-- ============================================================================
USE shifa_dashboard;
SET FOREIGN_KEY_CHECKS = 0;

-- --- Ledger / vouchers (children first) -------------------------------------
TRUNCATE TABLE ledger_source_postings;
TRUNCATE TABLE voucher_lines;
TRUNCATE TABLE vouchers;

-- --- Order-dependent detail tables (children of orders) ---------------------
TRUNCATE TABLE line_items;
TRUNCATE TABLE status_history;
TRUNCATE TABLE payments;
TRUNCATE TABLE order_payment_screenshots;
TRUNCATE TABLE order_shipments;
TRUNCATE TABLE courier_records;
TRUNCATE TABLE receivables;
TRUNCATE TABLE return_filings;

-- --- Leads (history first; leads may reference orders via converted_order_id)
TRUNCATE TABLE lead_status_history;
TRUNCATE TABLE leads;

-- --- Orders (parent of most of the above) -----------------------------------
TRUNCATE TABLE orders;

-- --- Standalone / cross-cutting tables --------------------------------------
TRUNCATE TABLE admin_notifications;
TRUNCATE TABLE audit_events;
TRUNCATE TABLE backup_runs;
TRUNCATE TABLE insights;
TRUNCATE TABLE outbox;
TRUNCATE TABLE sales_targets;

TRUNCATE TABLE purchase_orders;
TRUNCATE TABLE expenses;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- NOTE: dedicated sequence tables (e.g. invoice_sequence, purchase_order_sequence,
-- and the ledger voucher sequence) are NOT reset here. If you also want invoice /
-- order-code / voucher numbering to restart from the beginning, ask for those
-- resets to be added — but reusing numbers that appear in historical records or
-- backups can create duplicates.
-- ============================================================================
