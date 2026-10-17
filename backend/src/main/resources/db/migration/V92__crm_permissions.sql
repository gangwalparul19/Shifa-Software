-- CRM expansion Phase 3, Task 20 (crm-expansion spec, design §3.4) — a
-- configurable CRM permission layer on top of the fixed base roles. ADDITIVE,
-- with SAFE DEFAULTS that reproduce today's behaviour: enabling the layer
-- changes nothing until an admin customizes it (Req 19.3).
--
-- Resolution (PermissionService): an explicit user_id grant (granted true/false)
-- overrides a role grant; absent any row the code falls back to the SAFE DEFAULT
-- (so missing config = today). Each permission `or`-widens the base-role guard on
-- its endpoint — it never narrows today's access.
--
-- Conventions match V1 (InnoDB, utf8mb4). Highest version after this is V92.

CREATE TABLE crm_permissions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  permission VARCHAR(40) NOT NULL,
  role VARCHAR(20) NULL,
  user_id BIGINT NULL,
  granted BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  UNIQUE KEY ux_crm_perm (permission, role, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Safe-default seed = today's role gates:
--   * ASSIGN_LEADS      → ADMIN + TEAM_LEAD (lead assign/bulk-assign is ADMIN/TEAM_LEAD today)
--   * BULK_UPLOAD       → ADMIN (lead import is ADMIN today)
--   * MANAGE_PIPELINES  → ADMIN
--   * MANAGE_TAGS       → ADMIN
--   * MANAGE_SOURCES    → ADMIN
--   * VIEW_ALL_REPORTS  → ADMIN
INSERT INTO crm_permissions (permission, role, user_id, granted) VALUES
  ('ASSIGN_LEADS',     'ADMIN',     NULL, TRUE),
  ('ASSIGN_LEADS',     'TEAM_LEAD', NULL, TRUE),
  ('BULK_UPLOAD',      'ADMIN',     NULL, TRUE),
  ('MANAGE_PIPELINES', 'ADMIN',     NULL, TRUE),
  ('MANAGE_TAGS',      'ADMIN',     NULL, TRUE),
  ('MANAGE_SOURCES',   'ADMIN',     NULL, TRUE),
  ('VIEW_ALL_REPORTS', 'ADMIN',     NULL, TRUE);
