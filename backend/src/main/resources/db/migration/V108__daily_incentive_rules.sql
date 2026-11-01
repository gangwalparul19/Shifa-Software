-- Daily sales incentive rules.
--
-- Salespeople earn a flat bonus for each day their sales reach a slab (for example 25,000 -> 500,
-- 35,000 -> 750). The slabs are admin-managed and VERSIONED BY EFFECTIVE DATE:
--
--   * a rule set is immutable once saved; changing the rules saves a NEW rule set, so the old one
--     stays on record with who changed what and when (the audit trail);
--   * the bonus for a day uses the rule set in force on that day (latest effective_from on or
--     before it, latest id on a tie), so editing the rules never rewrites bonuses already earned;
--   * a rule set with no tiers means "no daily bonus from this date" (incentives switched off).
--
-- Additive. Nothing is seeded: the admin enters the slabs and the programme start date.

CREATE TABLE incentive_rule_sets (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    effective_from   DATE          NOT NULL,
    note             VARCHAR(255)  NULL,
    change_summary   VARCHAR(1000) NULL,
    created_by_id    BIGINT        NULL,
    created_by_name  VARCHAR(100)  NULL,
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_incentive_rule_sets PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_incentive_rule_sets_effective ON incentive_rule_sets (effective_from, id);

CREATE TABLE incentive_tiers (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    rule_set_id      BIGINT        NOT NULL,
    min_daily_sales  DECIMAL(12,2) NOT NULL,
    bonus_amount     DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_incentive_tiers PRIMARY KEY (id),
    CONSTRAINT fk_incentive_tiers_rule_set FOREIGN KEY (rule_set_id)
        REFERENCES incentive_rule_sets (id) ON DELETE CASCADE,
    CONSTRAINT uq_incentive_tiers_rule_min UNIQUE (rule_set_id, min_daily_sales)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
