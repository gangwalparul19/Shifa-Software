-- CRM expansion Phase 1 (crm-expansion spec, design §1.1).
--
-- Extends the leads aggregate so a lead's CREATOR is tracked separately from its
-- current ASSIGNEE (owner_user_id), marks admin-created leads ("Lead by Admin"),
-- and adds an optional follow-up time-of-day + the moment the current assignee
-- was set. Also introduces a managed Lead_Tag vocabulary (seeded with the five
-- call dispositions) and lead ↔ tag assignments.
--
-- All changes are strictly additive; existing leads keep working unchanged
-- (every new capability defaults to today's behaviour until configured).

-- leads: separate creator from assignee, mark admin-created, add follow-up time.
ALTER TABLE leads ADD COLUMN created_by_user_id BIGINT NULL;      -- original creator (backfill = owner_user_id)
ALTER TABLE leads ADD COLUMN created_by_admin   BOOLEAN NOT NULL DEFAULT FALSE; -- "Lead by Admin"
ALTER TABLE leads ADD COLUMN follow_up_time     TIME NULL;        -- optional time-of-day for the follow-up
ALTER TABLE leads ADD COLUMN assigned_at        DATETIME NULL;    -- when the current assignee was set
UPDATE leads SET created_by_user_id = owner_user_id WHERE created_by_user_id IS NULL;
CREATE INDEX ix_leads_created_by_admin ON leads (created_by_admin);

-- Lead-tag definitions (managed vocabulary). Seeded with the call dispositions.
CREATE TABLE lead_tags (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(40) NOT NULL,
  color VARCHAR(16) NULL,
  kind VARCHAR(20) NOT NULL DEFAULT 'CUSTOM',   -- DISPOSITION | CUSTOM
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY ux_lead_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Tag ↔ lead assignments (many-to-many).
CREATE TABLE lead_tag_assignments (
  id BIGINT NOT NULL AUTO_INCREMENT,
  lead_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY ux_lead_tag (lead_id, tag_id),
  KEY ix_lead_tag_lead (lead_id),
  CONSTRAINT fk_lead_tag_lead FOREIGN KEY (lead_id) REFERENCES leads (id),
  CONSTRAINT fk_lead_tag_def  FOREIGN KEY (tag_id)  REFERENCES lead_tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed the five call dispositions.
INSERT INTO lead_tags (name, kind, sort_order) VALUES
  ('Not Pick','DISPOSITION',1),('DND','DISPOSITION',2),('Busy','DISPOSITION',3),
  ('Wrong Number','DISPOSITION',4),('Call Back','DISPOSITION',5);
