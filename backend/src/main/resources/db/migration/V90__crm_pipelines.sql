-- CRM expansion Phase 3, Task 17 (crm-expansion spec, design §3.1) — configurable
-- pipelines & stages, introduced as an ADDITIVE CONFIGURATION OVERLAY on top of
-- the existing LeadStatus engine.
--
-- Strategy (deliberate deviation from design §3.1): the LeadStatus enum, the
-- leads.status column, and the frozen enum transition table REMAIN the
-- authoritative engine for the seeded "Default" pipeline. We do NOT drop
-- ck_leads_status — with this overlay, status only ever holds the five enum
-- values (custom stages are tracked via stage_id, never by writing arbitrary
-- names into status), so keeping the CHECK constraint is safer and avoids a
-- risky DDL. A later migration can relax it if free-form status is wanted.
--
-- This migration seeds ONE Default pipeline whose five stages map 1:1 to the
-- enum values (via legacy_status), then backfills every existing lead's
-- pipeline_id/stage_id from its current status. The status column stays
-- populated and authoritative for Default-pipeline leads, so all existing
-- LeadStatus-typed code, reports, and lead_status_history remain valid.
--
-- Additive only; never edits an applied migration. Conventions match V1
-- (InnoDB, utf8mb4). Highest version after this is V90.

-- -----------------------------------------------------------------------------
-- Pipelines
-- -----------------------------------------------------------------------------
CREATE TABLE pipelines (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY ux_pipeline_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Pipeline stages
--   terminal_kind : NULL | WON | LOST | CLOSED  (a terminal stage ends the funnel)
--   is_entry      : the single stage a new lead enters the pipeline at
--   legacy_status : for the Default pipeline only, maps a stage to the LeadStatus
--                   enum name so capture/transition can keep the status column
--                   authoritative and the backfill can resolve stage from status.
-- -----------------------------------------------------------------------------
CREATE TABLE pipeline_stages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  pipeline_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  sort_order INT NOT NULL,
  is_entry BOOLEAN NOT NULL DEFAULT FALSE,
  terminal_kind VARCHAR(10) NULL,
  legacy_status VARCHAR(16) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  KEY ix_pipeline_stages (pipeline_id, sort_order),
  CONSTRAINT fk_stage_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipelines (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- Lead → pipeline/stage structured references (nullable; status stays the
-- authoritative engine for Default-pipeline leads). FKs + lookup index.
-- ck_leads_status is intentionally KEPT (see header).
-- -----------------------------------------------------------------------------
ALTER TABLE leads ADD COLUMN pipeline_id BIGINT NULL;
ALTER TABLE leads ADD COLUMN stage_id BIGINT NULL;
ALTER TABLE leads
  ADD CONSTRAINT fk_leads_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipelines (id);
ALTER TABLE leads
  ADD CONSTRAINT fk_leads_stage FOREIGN KEY (stage_id) REFERENCES pipeline_stages (id);
CREATE INDEX ix_leads_pipeline_stage ON leads (pipeline_id, stage_id);

-- -----------------------------------------------------------------------------
-- Seed the Default pipeline + its five stages (1:1 with the LeadStatus enum).
-- The Default pipeline is resolved by is_default = TRUE (not a hard-coded id).
-- -----------------------------------------------------------------------------
INSERT INTO pipelines (name, is_default, active, sort_order) VALUES ('Default', TRUE, TRUE, 0);

INSERT INTO pipeline_stages (pipeline_id, name, sort_order, is_entry, terminal_kind, legacy_status, active)
SELECT id, 'New',       1, TRUE,  NULL,   'NEW',       TRUE FROM pipelines WHERE is_default = TRUE
UNION ALL
SELECT id, 'Contacted', 2, FALSE, NULL,   'CONTACTED', TRUE FROM pipelines WHERE is_default = TRUE
UNION ALL
SELECT id, 'Quoted',    3, FALSE, NULL,   'QUOTED',    TRUE FROM pipelines WHERE is_default = TRUE
UNION ALL
SELECT id, 'Won',       4, FALSE, 'WON',  'WON',       TRUE FROM pipelines WHERE is_default = TRUE
UNION ALL
SELECT id, 'Lost',      5, FALSE, 'LOST', 'LOST',      TRUE FROM pipelines WHERE is_default = TRUE;

-- -----------------------------------------------------------------------------
-- Backfill: point every existing lead at the Default pipeline and the stage
-- whose legacy_status equals the lead's current status. Pure set-based UPDATE
-- (Flyway-parseable; no stored proc).
-- -----------------------------------------------------------------------------
UPDATE leads l
JOIN pipelines p ON p.is_default = TRUE
JOIN pipeline_stages s ON s.pipeline_id = p.id AND s.legacy_status = l.status
SET l.pipeline_id = p.id,
    l.stage_id = s.id
WHERE l.pipeline_id IS NULL;
