-- CRM expansion Phase 2, Task 13 (design §2.5): extend the existing monthly
-- sales_targets (V38) with weekly + team targets and an optional conversion
-- goal (Req 12). Additive; existing monthly per-salesperson rows are preserved
-- and keep working (period_type defaults MONTH, team_lead_id NULL).
ALTER TABLE sales_targets ADD COLUMN period_type       VARCHAR(10)   NOT NULL DEFAULT 'MONTH'; -- WEEK | MONTH
ALTER TABLE sales_targets ADD COLUMN team_lead_id      BIGINT        NULL;   -- non-null = a TEAM target
ALTER TABLE sales_targets ADD COLUMN conversion_target DECIMAL(5,2)  NULL;   -- optional % goal

-- A team target has salesperson_id NULL (keyed by team_lead_id instead), so
-- relax salesperson_id to nullable. Existing per-salesperson rows are unaffected.
ALTER TABLE sales_targets MODIFY COLUMN salesperson_id BIGINT NULL;

-- Relax the uniqueness from (salesperson_id, period_month) to the full period key
-- (salesperson_id, team_lead_id, period_type, period_month). Existing monthly
-- rows (period_type='MONTH', team_lead_id NULL) still satisfy the new key.
ALTER TABLE sales_targets DROP INDEX ux_sales_target;
ALTER TABLE sales_targets
  ADD UNIQUE KEY ux_sales_target (salesperson_id, team_lead_id, period_type, period_month);
