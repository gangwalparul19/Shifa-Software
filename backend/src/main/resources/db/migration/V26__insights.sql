-- =============================================================================
-- Shifa Herbal Remedies OMS - statistical insights engine (V26)
--
-- Statistical Insights Engine, data model (design §Data Model). Introduces the
-- compact insights table the nightly batch writes and the dashboard reads
-- instantly:
--
--  * insights - one computed insight per (insight_type, scope, scope_ref_id,
--               computed_date). The natural key is enforced by ux_insights_natural
--               so a same-date recompute replaces rather than duplicates; GLOBAL
--               scope rows use the sentinel scope_ref_id = 0 (set by the entity
--               from(Insight) factory) to keep that key unique per type+date.
--               dismissed / dismissed_at / dismissed_by carry the admin dismiss
--               state; created_at is a DB default.
--
-- Additive only (new table + indexes): safe on the seeded V22 dataset with no
-- backfill. No FKs on scope_ref_id (it references different entity kinds per
-- scope). V25 is the previous highest version; never edit an applied migration.
-- Conventions match V1/V25 (InnoDB, utf8mb4, pk_/ck_ naming).
-- =============================================================================

CREATE TABLE insights (
  id BIGINT NOT NULL AUTO_INCREMENT,
  insight_type VARCHAR(40) NOT NULL,
  scope VARCHAR(20) NOT NULL,
  scope_ref_id BIGINT NULL,
  scope_label VARCHAR(200) NULL,
  severity VARCHAR(20) NOT NULL,
  title VARCHAR(200) NOT NULL,
  detail TEXT NULL,
  metric_value DECIMAL(18,4) NULL,
  computed_date DATE NOT NULL,
  dismissed BOOLEAN NOT NULL DEFAULT FALSE,
  dismissed_at DATETIME NULL,
  dismissed_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_insights PRIMARY KEY (id),
  CONSTRAINT ck_insights_scope CHECK (scope IN ('GLOBAL','PRODUCT','COURIER','SALESPERSON','ORDER')),
  CONSTRAINT ck_insights_severity CHECK (severity IN ('INFO','WARNING','DANGER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_insights_computed_date ON insights (computed_date);
CREATE INDEX ix_insights_type_date ON insights (insight_type, computed_date);
CREATE INDEX ix_insights_scope ON insights (scope, scope_ref_id);
CREATE UNIQUE INDEX ux_insights_natural ON insights (insight_type, scope, scope_ref_id, computed_date);
