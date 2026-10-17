-- CRM expansion Phase 3, Task 19 (crm-expansion spec, design §3.2, §3.3) —
-- admin-managed lead sources, stage-scoped tags, and a single-row CRM settings
-- table. Additive only; every capability defaults to today's behaviour.
--
--  * lead_sources : a managed availability list for the capture/upload/report
--    pickers, SEEDED from the current com.shifa.oms.order.LeadSource enum. The
--    enum stays the compile-time default and leads.lead_source is unchanged
--    (string values unchanged) — the table drives only the UI picker + the
--    admin-managed availability list.
--  * lead_tags.stage_id : optional association of a tag with a pipeline stage
--    (Req 18.1), nullable FK → pipeline_stages (V90).
--  * crm_settings : single-row CRM configuration (follow-up default time, missed
--    grace window, default pipeline, default assignment rule), mirroring the
--    single-row app_settings pattern.
--
-- Conventions match V1 (InnoDB, utf8mb4). Highest version after this is V91.

-- -----------------------------------------------------------------------------
-- Managed lead-source availability list (seeded from the LeadSource enum).
-- -----------------------------------------------------------------------------
CREATE TABLE lead_sources (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(40) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY ux_lead_source_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed every current LeadSource enum value (read from LeadSource.java).
INSERT INTO lead_sources (name, active, sort_order) VALUES
  ('WHATSAPP', TRUE, 1),
  ('INSTAGRAM', TRUE, 2),
  ('FACEBOOK', TRUE, 3),
  ('OFFLINE', TRUE, 4),
  ('SHOPIFY_UPSELL', TRUE, 5),
  ('SHOPIFY_ABANDONMENT_SALE', TRUE, 6),
  ('INBOUND_CALLS', TRUE, 7),
  ('REPEAT_CUSTOMER', TRUE, 8),
  ('REFERRAL', TRUE, 9),
  ('OTHER', TRUE, 10),
  ('SHOPIFY', TRUE, 11),
  ('COUNTER_SALE', TRUE, 12);

-- -----------------------------------------------------------------------------
-- Optional stage association for a tag (Req 18.1). Nullable FK → pipeline_stages.
-- -----------------------------------------------------------------------------
ALTER TABLE lead_tags ADD COLUMN stage_id BIGINT NULL;
ALTER TABLE lead_tags
  ADD CONSTRAINT fk_lead_tag_stage FOREIGN KEY (stage_id) REFERENCES pipeline_stages (id);

-- -----------------------------------------------------------------------------
-- Single-row CRM settings (mirrors app_settings). Seeded with one defaults row.
--   default_assignment_rule : DIRECT | ROUND_ROBIN
-- -----------------------------------------------------------------------------
CREATE TABLE crm_settings (
  id TINYINT NOT NULL DEFAULT 1,
  follow_up_default_time TIME NULL,
  missed_grace_hours INT NOT NULL DEFAULT 24,
  default_pipeline_id BIGINT NULL,
  default_assignment_rule VARCHAR(20) NOT NULL DEFAULT 'DIRECT',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  CONSTRAINT fk_crm_settings_pipeline FOREIGN KEY (default_pipeline_id) REFERENCES pipelines (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the single defaults row, pointing default_pipeline_id at the Default pipeline.
INSERT INTO crm_settings (id, follow_up_default_time, missed_grace_hours, default_pipeline_id, default_assignment_rule)
SELECT 1, '09:00:00', 24, p.id, 'DIRECT'
FROM pipelines p
WHERE p.is_default = TRUE;
