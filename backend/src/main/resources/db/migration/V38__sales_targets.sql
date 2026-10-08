-- Monthly sales targets & incentive tracking per salesperson (FEATURE-ROADMAP §6.1).
--
-- An admin sets a monthly revenue target (and an optional incentive %) for each
-- salesperson; the dashboard/leaderboard compares it to achieved revenue to show
-- attainment and a computed incentive. `period_month` is stored as the first day
-- of the month. At most one target per (salesperson, month).
CREATE TABLE sales_targets (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    salesperson_id BIGINT        NOT NULL,
    period_month   DATE          NOT NULL,
    target_amount  DECIMAL(12,2) NOT NULL,
    incentive_pct  DECIMAL(5,2)  NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_sales_target (salesperson_id, period_month),
    KEY ix_sales_targets_month (period_month),
    CONSTRAINT fk_sales_target_user FOREIGN KEY (salesperson_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
