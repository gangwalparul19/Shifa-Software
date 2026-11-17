-- Salesperson/portal order policy: every upfront payment must identify the
-- receiving bank, QR, cash, or other configured account before the order is punched.
-- The payment-method master remains database-managed, but no-account methods are
-- no longer valid for salesperson order entry.
--
-- Shopify imports and STORE/POS orders use separate flows and are intentionally
-- unchanged by this migration.

UPDATE payment_methods
SET requires_account = TRUE;
