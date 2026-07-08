-- =============================================================================
-- Shifa Herbal Remedies OMS - lead management & sales pipeline (V25)
--
-- Lead Management, data model (design §3.2, §3.3, §3.4). Introduces the pre-order
-- Lead aggregate and its status-history trail:
--
--  * leads               - a prospective sale captured before it becomes an
--                          order, owned by a salesperson (or the acting admin),
--                          worked NEW -> CONTACTED -> QUOTED and terminated as
--                          WON (Convert-only) / LOST (with a categorized reason).
--                          lead_source reuses the existing LeadSource enum;
--                          reminded_on de-dups the daily follow-up reminder job
--                          (design §Follow-up Reminders).
--  * lead_status_history - one row per accepted status change (from/to/actor/
--                          timestamp), plus one creation row (from_status NULL)
--                          recording the initial NEW status (Req 2.6, 7.5).
--
-- Additive only (new tables + indexes): safe on the seeded V22 dataset with no
-- backfill. V24 is the previous highest version; never edit an applied
-- migration. Conventions match V1 (InnoDB, utf8mb4).
-- =============================================================================

CREATE TABLE leads (
  id BIGINT NOT NULL AUTO_INCREMENT,
  customer_name VARCHAR(120) NOT NULL,
  customer_mobile VARCHAR(10) NULL,
  customer_email VARCHAR(150) NULL,
  lead_source VARCHAR(20) NOT NULL,
  lead_source_note VARCHAR(200) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'NEW',
  lost_reason VARCHAR(20) NULL,
  lost_reason_note VARCHAR(200) NULL,
  note VARCHAR(1000) NULL,
  follow_up_date DATE NULL,
  reminded_on DATE NULL,
  owner_user_id BIGINT NOT NULL,
  converted_order_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT pk_leads PRIMARY KEY (id),
  CONSTRAINT fk_leads_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
  CONSTRAINT fk_leads_order FOREIGN KEY (converted_order_id) REFERENCES orders (id),
  CONSTRAINT ck_leads_status CHECK (status IN ('NEW','CONTACTED','QUOTED','WON','LOST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_leads_owner_status ON leads (owner_user_id, status);
CREATE INDEX ix_leads_status ON leads (status);
CREATE INDEX ix_leads_follow_up ON leads (follow_up_date, status);
CREATE INDEX ix_leads_source ON leads (lead_source);
CREATE INDEX ix_leads_mobile ON leads (customer_mobile);

CREATE TABLE lead_status_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  lead_id BIGINT NOT NULL,
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NOT NULL,
  actor VARCHAR(100) NOT NULL,
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_lead_status_history PRIMARY KEY (id),
  CONSTRAINT fk_lead_history_lead FOREIGN KEY (lead_id) REFERENCES leads (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_lead_history_lead ON lead_status_history (lead_id);
