-- =============================================================================
-- Shifa Herbal Remedies OMS - General Ledger seed data (V55)
--
-- Spec: general-ledger-accounting (Phase 1). Seeds, into the V54 tables, the
-- "on first initialisation" content the accounting module requires:
--
--   * the five-nature default account groups (Tally-style), Req 1.6;
--   * the mapped control ledgers, each with its control_key so auto-posting can
--     resolve a control role -> ledger id, Req 2.5;
--   * the current + prior Indian financial years (1 Apr - 31 Mar) as EXPLICIT,
--     deterministic dates (prior 2025-26, current 2026-27) - never derived from
--     NOW()/CURDATE(), so the seed produces the same two years and labels on
--     every machine and every re-run.
--
-- Idempotency / convergence (safe on manual re-run):
--   Every row is inserted via INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS
--   keyed on a natural key (group name+parent, ledger control_key, FY
--   start_date). This is deliberately used INSTEAD of INSERT ... ON DUPLICATE KEY
--   UPDATE because the default account groups are all top-level
--   (parent_group_id IS NULL) and MySQL treats NULLs in a UNIQUE index as
--   DISTINCT - so ON DUPLICATE KEY UPDATE would never fire for them and a re-run
--   would duplicate the groups. WHERE NOT EXISTS converges correctly for
--   NULL-parent rows. (FROM DUAL makes the guarded single-row insert unambiguous.)
--
-- Parent/child references are resolved by NAME via subselect (never by hardcoded
-- AUTO_INCREMENT id), so the child ledgers attach to the groups this same
-- migration just created regardless of the assigned ids.
--
-- Plain versioned SQL only - no DELIMITER / stored procedures - so Flyway parses
-- it statement-by-statement. No session variables and no CURDATE()/NOW() logic:
-- the financial-year rows are hardcoded explicit dates for deterministic output.
--
-- NOTE ON GST INPUT PLACEMENT: the design seed table pairs the GST Input control
-- ledger with control_key GST_INPUT and nature ASSET. A ledger's nature is
-- DERIVED from its group (V54: ledger_accounts stores no nature), so to be an
-- ASSET it must sit under an ASSET group. "Duties & Taxes" is a LIABILITY group
-- (it holds GST Output, a liability), so GST Input is placed under the "Current
-- Assets" ASSET group - input GST credit is a recoverable asset. This honours the
-- required GST_INPUT/ASSET nature and keeps the Dr/Cr sign convention correct.
--
-- Requirements: 1.6, 2.5
-- =============================================================================

-- ============================================================================
-- 1) Default account groups (five natures) - all top-level (parent_group_id NULL)
-- ============================================================================

-- ---- ASSET ------------------------------------------------------------------
INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Current Assets', 'ASSET', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Current Assets' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Fixed Assets', 'ASSET', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Fixed Assets' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Cash-in-Hand', 'ASSET', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Cash-in-Hand' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Bank Accounts', 'ASSET', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Bank Accounts' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Sundry Debtors', 'ASSET', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Sundry Debtors' AND parent_group_id IS NULL);

-- ---- LIABILITY --------------------------------------------------------------
INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Current Liabilities', 'LIABILITY', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Current Liabilities' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Duties & Taxes', 'LIABILITY', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Duties & Taxes' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Sundry Creditors', 'LIABILITY', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Sundry Creditors' AND parent_group_id IS NULL);

-- ---- INCOME -----------------------------------------------------------------
INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Sales Accounts', 'INCOME', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Sales Accounts' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Direct Income', 'INCOME', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Direct Income' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Indirect Income', 'INCOME', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Indirect Income' AND parent_group_id IS NULL);

-- ---- EXPENSE ----------------------------------------------------------------
INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Purchase Accounts', 'EXPENSE', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Purchase Accounts' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Direct Expenses', 'EXPENSE', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Direct Expenses' AND parent_group_id IS NULL);

INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Indirect Expenses', 'EXPENSE', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Indirect Expenses' AND parent_group_id IS NULL);

-- ---- EQUITY -----------------------------------------------------------------
INSERT INTO account_groups (name, nature, parent_group_id, system_generated)
SELECT 'Capital Account', 'EQUITY', NULL, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM account_groups WHERE name = 'Capital Account' AND parent_group_id IS NULL);

-- ============================================================================
-- 2) Mapped control ledgers - each under its group (resolved by NAME) with its
--    control_key. Nature is derived from the group at read time (V54).
-- ============================================================================

-- Sundry Debtors (ASSET) -> group "Sundry Debtors"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Sundry Debtors',
       (SELECT id FROM account_groups WHERE name = 'Sundry Debtors' AND parent_group_id IS NULL),
       'SUNDRY_DEBTORS', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'SUNDRY_DEBTORS');

-- Sundry Creditors (LIABILITY) -> group "Sundry Creditors"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Sundry Creditors',
       (SELECT id FROM account_groups WHERE name = 'Sundry Creditors' AND parent_group_id IS NULL),
       'SUNDRY_CREDITORS', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'SUNDRY_CREDITORS');

-- Sales (INCOME) -> group "Sales Accounts"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Sales',
       (SELECT id FROM account_groups WHERE name = 'Sales Accounts' AND parent_group_id IS NULL),
       'SALES', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'SALES');

-- Purchases (EXPENSE) -> group "Purchase Accounts"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Purchases',
       (SELECT id FROM account_groups WHERE name = 'Purchase Accounts' AND parent_group_id IS NULL),
       'PURCHASES', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'PURCHASES');

-- GST Output (LIABILITY) -> group "Duties & Taxes"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'GST Output',
       (SELECT id FROM account_groups WHERE name = 'Duties & Taxes' AND parent_group_id IS NULL),
       'GST_OUTPUT', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'GST_OUTPUT');

-- GST Input (ASSET) -> group "Current Assets" (see header note on placement)
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'GST Input',
       (SELECT id FROM account_groups WHERE name = 'Current Assets' AND parent_group_id IS NULL),
       'GST_INPUT', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'GST_INPUT');

-- Cash (ASSET) -> group "Cash-in-Hand"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Cash',
       (SELECT id FROM account_groups WHERE name = 'Cash-in-Hand' AND parent_group_id IS NULL),
       'CASH', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'CASH');

-- Bank (ASSET) -> group "Bank Accounts"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'Bank',
       (SELECT id FROM account_groups WHERE name = 'Bank Accounts' AND parent_group_id IS NULL),
       'BANK', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'BANK');

-- General Expenses (EXPENSE, default expense ledger) -> group "Indirect Expenses"
INSERT INTO ledger_accounts (name, account_group_id, control_key, system_generated)
SELECT 'General Expenses',
       (SELECT id FROM account_groups WHERE name = 'Indirect Expenses' AND parent_group_id IS NULL),
       'DEFAULT_EXPENSE', TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ledger_accounts WHERE control_key = 'DEFAULT_EXPENSE');

-- ============================================================================
-- 3) Financial years - the current + prior Indian FY (1 Apr - 31 Mar) as EXPLICIT
--    deterministic dates (NOT CURDATE()/NOW()-derived), so the seed always yields
--    the same two years and labels regardless of when/where it runs. These two
--    FYs also line up with the seeded order data, which spans FY2025-26 and
--    FY2026-27. Keyed on start_date (unique in V54); WHERE NOT EXISTS converges
--    on re-run (a plain string in a DATE context is implicitly a DATE).
-- ============================================================================

-- Prior financial year: 2025-26  (1 Apr 2025 - 31 Mar 2026)
INSERT INTO financial_years (start_date, end_date, label, closed)
SELECT '2025-04-01', '2026-03-31', '2025-26', FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM financial_years WHERE start_date = '2025-04-01');

-- Current financial year: 2026-27  (1 Apr 2026 - 31 Mar 2027)
INSERT INTO financial_years (start_date, end_date, label, closed)
SELECT '2026-04-01', '2027-03-31', '2026-27', FALSE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM financial_years WHERE start_date = '2026-04-01');
