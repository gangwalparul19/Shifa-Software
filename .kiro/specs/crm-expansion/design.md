# Design Document

## Overview

This design turns the CRM proposal into a buildable plan that extends the existing Shifa OMS without
rewrites. It reuses the shipped **lead** aggregate (`V25`), **customer 360** tags/notes (`V34`),
**sales targets** (`V38`), **WhatsApp templates** (`V44`), **teams** (`V43`), the **outbox +
`StaffNotificationDispatcher` + Web Push** rails, the **`AuditService`** trail, the **`SpreadsheetParser`**
(CSV/Excel), the **`PincodeService`**, and the webhook/outbox pattern used by the courier and Shopify
integrations. The highest applied migration is **V82**; new migrations start at **V83** and are strictly
additive (an applied migration is never edited).

Delivery is phased (matching `requirements.md`). Phases 1–3 need no third-party accounts; Phase 4 is
gated on client-supplied provider credentials. A guiding constraint: **every new capability defaults to
today's behavior** until an admin configures it, so each phase can ship safely and independently.

## Architecture & module layout

New/extended backend packages under `com.shifa.oms`:

| Package | Role | Phase |
|---|---|---|
| `lead` (existing) | Extend: assignment, tags, admin-marker, follow-up time, bulk upload, activity | 1–3 |
| `crm` (existing) | Extend: unified customer+lead activity timeline | 2 |
| `activity` (new) | `ActivityEntry` aggregate + `ActivityService` — the unified Activity log/timeline | 2 |
| `task` (new) | `TaskEntity` + `TaskService` + `TaskController` | 2 |
| `meeting` (new) | `MeetingEntity` + `MeetingService` + `MeetingController` | 2 |
| `performance` (existing) | Extend: weekly + team targets, weekly performance, team-wise reports | 2 |
| `pipeline` (new) | `Pipeline` + `PipelineStage` + `PipelineService` (configurable stages) | 3 |
| `leadtag` (new, or sub of `lead`) | `LeadTag` definition + `LeadTagAssignment` + service | 1/3 |
| `crmsettings` (new, thin) | Admin CRM config surface (sources, follow-up defaults, grace window) | 3 |
| `permission` (new) | `CrmPermission` grants + `PermissionService` + `@PreAuthorize` SpEL helper | 3 |
| `leadchannel` (new) | Inbound connectors: Meta Lead Ads, Google/Gallbox, Website webhook | 4 |
| `notification`/`whatsapp` (existing) | Replace mock `WhatsAppClient` with a real provider impl (config-gated) | 4 |

Frontend (`frontend/projects/admin/src/app`): extend `leads/`, `customers/`, `dashboard/`,
`reports/`, `settings/`, `users/`; new features `tasks/`, `meetings/`, `crm-dashboard/`,
`pipelines/` (settings), `crm-settings/`. All mobile-first, reusing the shared components
(`page-header`, `pagination`, `state-panel`, status badges, `IstDatePipe`, `whatsapp.util`,
`pincode.service`).

---

## Phase 1 — Lead ownership, bulk intake, tags & capture

### 1.1 Data model (migration **V83** `crm_lead_assignment_tags_followup_time`)

Extends `leads` and adds tag tables. Additive; existing leads keep working.

```sql
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
```

**Design decisions:**
- **Assignee = `owner_user_id`** (keeps existing scoping/queries intact); the NEW `created_by_user_id`
  preserves the original creator across reassignment (R1.1). `created_by_admin` is the "Lead by Admin"
  marker (R3).
- **Follow-up time** is a nullable `TIME` alongside the existing `follow_up_date`; a null time means
  "use the configured default time-of-day" so existing date-only leads are unchanged (R6.1).
- **Tags are a managed vocabulary + assignments** (not free strings) so Phase 3 can scope/rename/retire
  them; the five dispositions are seeded (R4.2).

### 1.2 Backend

- `LeadEntity`: add `createdByUserId`, `createdByAdmin`, `followUpTime`, `assignedAt` (+ getters/setters).
  `LeadStatusHistory` already covers stage changes; assignment/tag changes are logged via the new
  **Activity** log (Phase 2) — in Phase 1 they are recorded via `AuditService` and a lightweight
  activity row created once the `activity` package lands (Phase 1 writes audit; Phase 2 adds the
  timeline read model — no rework, just an added writer).
- `LeadService`:
  - `assign(id, toUserId, actor)` / `bulkAssign(ids, toUserId, actor)` — ADMIN/TEAM_LEAD only; updates
    `owner_user_id` + `assigned_at`, audits, notifies the new assignee (R1, R15.1). Team assignment
    rule (R1.3): **assign to the Team_Lead by default**, with an optional round-robin across team
    members selectable in the request (design keeps it explicit, not magical).
  - `addTag(id, tagId, actor)` / `removeTag(id, tagId, actor)` — allowed on terminal leads too (R5.1);
    audits + activity.
  - `setFollowUp(id, date, time, actor)` — extend the existing signature with an optional time; re-arm
    reminder (R6).
  - `capture`/bulk mark `created_by_admin = (actor.role == ADMIN)` and set `created_by_user_id` (R3.1).
- `LeadBulkImportService` (new) — uses `com.shifa.oms.common.SpreadsheetParser.parse(bytes, filename)`
  (the same parser the remittance import uses). Column aliases: name, mobile, email, source, note,
  assignee (username/id), tags (comma-sep), followUpDate, followUpTime. Per-row validate→create, caps at
  `app.crm.lead-import.max-rows` (default 2000), returns `LeadImportResult {created, skipped[]}` (R2).
- `LeadController` / new endpoints (class role widened to include TEAM_LEAD where appropriate; ADMIN-only
  for assign/import):
  - `PUT /api/leads/{id}/assign` (ADMIN/TEAM_LEAD), `POST /api/leads/bulk-assign`
  - `POST /api/leads/{id}/tags` / `DELETE /api/leads/{id}/tags/{tagId}`
  - `GET /api/leads/tags` (list active) ; admin tag CRUD under `/api/admin/lead-tags` (Phase 3 extends)
  - `POST /api/admin/leads/import` (multipart; ADMIN) → `LeadImportResult`
  - list filters extended: `?assignedTo=`, `?tag=`, `?createdByAdmin=` 
- Pincode in lead capture (R7): **frontend-only** — inject the existing `PincodeService` into the leads
  capture form (no backend change).

### 1.3 Frontend
- Leads list: "Lead by Admin" badge, tag chips, filters (assignee, tag, admin-created). Drawer: tag
  add/remove (incl. on terminal leads), follow-up date **+ time** picker, and (ADMIN/TEAM_LEAD) an
  Assign/Reassign control + bulk-select → "Assign N".
- New admin **Import leads** action (file picker → result summary, mirroring the remittance import UX).
- Lead capture form: pincode→city/state autofill via `PincodeService` (as New Order already does).

---

## Phase 2 — Activity model, monitoring & performance

### 2.1 Unified Activity log (migration **V84** `crm_activities`)

One append-only table is the backbone of the timeline (R11) and the "was the follow-up acted on?"
check (R10).

```sql
CREATE TABLE crm_activities (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_type VARCHAR(40) NOT NULL,     -- STAGE_CHANGE, TAG_ADDED, TAG_REMOVED, NOTE, ASSIGNED,
                                          -- FOLLOW_UP_SET, FOLLOW_UP_MISSED, TASK_CREATED, TASK_DONE,
                                          -- MEETING_SCHEDULED, MEETING_OUTCOME, CALL, WHATSAPP, ...
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,       -- for customer-timeline aggregation (Customer 360 keying)
  actor_user_id BIGINT NULL,
  actor_name VARCHAR(150) NULL,
  summary VARCHAR(500) NOT NULL,          -- human line ("Stage NEW → CONTACTED", "Tagged DND")
  detail VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_activity_lead (lead_id, created_at),
  KEY ix_activity_customer (customer_mobile, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- `activity` package: `ActivityEntry` entity, `ActivityRepository` (findByLead / findByCustomerMobile),
  `ActivityService.record(...)` (the single writer) + `timelineForLead(id)` / `timelineForCustomer(mobile)`.
- Writers are added at each touch point: `LeadService` (stage/tag/assign/follow-up), `TaskService`,
  `MeetingService`, and the frontend call/WhatsApp actions post a lightweight `CALL`/`WHATSAPP` activity
  via a small `POST /api/activities/log` (actor-scoped). Existing `lead_status_history` stays as the
  authoritative stage trail; `crm_activities` is the superset read model.
- Customer timeline (R11.4) = union of `crm_activities` for the mobile + the customer's order history
  (already in Customer 360) ordered by time.

### 2.2 Tasks (migration **V85** `tasks`)

```sql
CREATE TABLE tasks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NULL,
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,
  due_at DATETIME NULL,
  priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL',  -- LOW | NORMAL | HIGH
  status VARCHAR(12) NOT NULL DEFAULT 'OPEN',      -- OPEN | DONE | CANCELLED
  completed_at DATETIME NULL,
  reminded_on DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_tasks_owner_status (owner_user_id, status),
  KEY ix_tasks_due (due_at, status),
  CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
- `task` package: entity/repo/service/controller. Endpoints `GET/POST /api/tasks`, `PUT /api/tasks/{id}`,
  `POST /api/tasks/{id}/complete`; scoped (own; TEAM_LEAD/ADMIN read team/all). `TaskReminderJob`
  (`@Scheduled`, mirrors `FollowUpReminderJob`) notifies owners of due tasks, idempotent via `reminded_on`.

### 2.3 Meetings (migration **V86** `meetings`)

```sql
CREATE TABLE meetings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,
  start_at DATETIME NOT NULL,
  mode VARCHAR(20) NULL,            -- CALL | VIDEO | IN_PERSON
  location VARCHAR(200) NULL,
  notes VARCHAR(1000) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED | DONE | CANCELLED
  outcome VARCHAR(1000) NULL,
  reminded_on DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_meetings_owner (owner_user_id, start_at),
  CONSTRAINT fk_meetings_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
- `meeting` package: entity/repo/service/controller; reschedule/cancel/record-outcome; `MeetingReminderJob`
  for upcoming meetings.

### 2.4 Missed follow-ups (R10)
- Reuse `leads.follow_up_date`/`follow_up_time` + the existing `reminded_on`. Add a nullable
  `leads.follow_up_missed_at DATETIME` (migration **V87**). `FollowUpReminderJob` already notifies on due;
  a new `MissedFollowUpJob` runs after the grace window (`app.crm.followup.grace-hours`, default 24) and,
  for leads still due with no activity since the reminder (checked against `crm_activities`), stamps
  `follow_up_missed_at`, records a `FOLLOW_UP_MISSED` activity, notifies the assignee, and escalates to the
  Team_Lead/Admin. "Missed list" = leads with `follow_up_missed_at` set and still unresolved.

### 2.5 Weekly & team targets (migration **V88** `extend sales_targets`)
Extend the existing `sales_targets` (don't replace — monthly already shipped):
```sql
ALTER TABLE sales_targets ADD COLUMN period_type VARCHAR(10) NOT NULL DEFAULT 'MONTH'; -- WEEK | MONTH
ALTER TABLE sales_targets ADD COLUMN team_lead_id BIGINT NULL;   -- non-null = a TEAM target
ALTER TABLE sales_targets ADD COLUMN conversion_target DECIMAL(5,2) NULL; -- optional % goal
-- relax the unique key to (salesperson_id, team_lead_id, period_type, period_month)
```
- `SalesTargetService` extended for week/team + `conversionTarget`; `period_month` reused as the period
  anchor (week = that week's Monday). Monthly per-salesperson behavior preserved (R12.4).

### 2.6 Reports & cockpit (R13, R14)
- Extend `LeadReportAggregator` / `LeadService` reports with **by-stage**, **by-user**, **by-team**
  (reusing the existing by-source/conversion/pipeline/lost-reason reports), role-scoped.
- **CRM cockpit**: a new `crm-dashboard` admin view that composes existing endpoints (lead pipeline,
  follow-up health incl. missed, performance/targets, sales/customer headline) — assembly, not new
  computation (R14.3). Backend adds a thin `GET /api/admin/crm/overview` that fans out to existing
  services (so one call powers the cockpit).

### 2.7 Notifications (R15) — all via existing rails
`StaffNotificationDispatcher.dispatchToUser` for: new-lead-assigned, task-due, meeting-upcoming,
follow-up-due/missed. New notification `type` constants; the bell + SSE + Web Push already fan these out.

---

## Phase 3 — Configurable pipelines, stages, tags & permissions

### 3.1 Pipelines & stages (migration **V89** `crm_pipelines`)
```sql
CREATE TABLE pipelines (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY ux_pipeline_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pipeline_stages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  pipeline_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  sort_order INT NOT NULL,
  is_entry BOOLEAN NOT NULL DEFAULT FALSE,
  terminal_kind VARCHAR(10) NULL,     -- NULL | WON | LOST | CLOSED
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  KEY ix_pipeline_stages (pipeline_id, sort_order),
  CONSTRAINT fk_stage_pipeline FOREIGN KEY (pipeline_id) REFERENCES pipelines (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Lead → pipeline/stage. Keep the old `status` column for back-compat/history;
-- add structured references. Drop the fixed CHECK constraint.
ALTER TABLE leads DROP CHECK ck_leads_status;        -- (MySQL 8) frees the status vocabulary
ALTER TABLE leads ADD COLUMN pipeline_id BIGINT NULL;
ALTER TABLE leads ADD COLUMN stage_id BIGINT NULL;
```
**Migration of existing data (R16.3, R17.3):** seed a **"Default" pipeline** whose stages reproduce the
current funnel (New → Contacted → Quoted, terminal Won/Lost), then backfill every existing lead's
`pipeline_id`/`stage_id` from its current `status`. The legacy `status` string is kept populated (mapped
from the stage) so existing reports/history and the `lead_status_history` trail stay valid; new code
reads `stage_id`.

- `pipeline` package: `Pipeline`/`PipelineStage` entities, `PipelineService` (CRUD + reorder + designate
  entry/terminal), `PipelineController` (`/api/admin/pipelines`, ADMIN). Transition legality moves from
  the hard-coded `LeadStatus` table to **stage configuration** (an admin can allow any forward/backward
  edges; default seed reproduces today's forward-only funnel). A **compatibility shim** keeps the
  `LeadStatus` enum values mapping onto the Default pipeline's stages so unconverted call sites don't break
  during rollout.
- Re-open (R5.2) becomes a configured transition out of a terminal stage (audited), not a special case.

### 3.2 Lead-source management (migration **V90** `lead_sources`)
Promote the `LeadSource` enum usage to a managed table (`lead_sources`: name, active, sort) seeded from the
current enum values; capture/upload/reporting read the table. The enum stays as a compile-time default/seed
so existing code and the `leads.lead_source` column keep working (string values unchanged).

### 3.3 CRM settings surface (R18.3)
Thin `crmsettings` admin area (reuses the existing `app_settings` pattern or a small `crm_settings` K/V):
follow-up default time, missed grace window, default pipeline, default assignment rule. Admin UI under
Settings.

### 3.4 Configurable permissions (migration **V91** `crm_permissions`)
```sql
CREATE TABLE crm_permissions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  permission VARCHAR(40) NOT NULL,     -- ASSIGN_LEADS, BULK_UPLOAD, MANAGE_PIPELINES, MANAGE_TAGS, VIEW_ALL_REPORTS, ...
  role VARCHAR(20) NULL,               -- grant to a role …
  user_id BIGINT NULL,                 -- … or an individual user
  granted BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (id),
  UNIQUE KEY ux_crm_perm (permission, role, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
- `permission` package: `PermissionService.has(principal, perm)` consulted by a custom SpEL method so
  endpoints use `@PreAuthorize("@perms.has(authentication,'ASSIGN_LEADS')")` **in addition to** the base
  role (defense-in-depth). **Safe defaults** are seeded to reproduce today's behavior (e.g. ADMIN has all;
  TEAM_LEAD has ASSIGN_LEADS for their team), so enabling the layer changes nothing until customized
  (R19.3). The frontend reads an effective-permissions endpoint to hide unavailable actions.

---

## Phase 4 — External integrations (provider-dependent)

### 4.1 Lead-channel ingestion (`leadchannel` package; migration **V92** `lead_channel_log` if needed)
- A connector per channel following the **existing webhook pattern** (`CourierWebhookController` /
  `ShopifyWebhookController` under `/api/webhooks/**`, HMAC/token verified, `permitAll` + signature check):
  - `POST /api/webhooks/meta/leads` — Meta Lead Ads (Instagram + Facebook); verify `X-Hub-Signature-256`,
    handle the subscription challenge, fetch the lead payload.
  - `POST /api/webhooks/google-leads` (Google/Gallbox) and `POST /api/webhooks/website-leads` (site form;
    shared-secret token).
- Each verified payload → `LeadIngestionService.ingest(channel, payload)`: map to a lead with the correct
  source, **de-dupe** by mobile/email within a window, apply the configured **assignment rule**, record an
  ingestion **activity**, and push failures to an admin via the **outbox retry** (exactly like courier/Shopify
  so a transient failure never drops a lead). Each channel is independently enable-able and **no-ops when its
  credentials are unset** (R20.4).

### 4.2 Real WhatsApp provider (replace the mock behind `WhatsAppClient`)
- Implement `HttpWhatsAppClient implements WhatsAppClient`, `@ConditionalOnProperty(app.whatsapp.mode=LIVE)`
  (the existing `MockWhatsAppClient` stays `matchIfMissing=true`, so **mock remains the default**). Wire the
  BSP's send-template/send-session API + credentials via `WhatsAppProperties`.
- The existing `WhatsAppOutboxDrainer` + `NotificationMatrix` sends flow through unchanged — they just hit the
  live client when configured. Send/delivery callbacks (if the BSP supports them) arrive on a verified webhook
  and are recorded as `WHATSAPP` activities on the lead/customer timeline (R21.2, R21.3). Click-to-chat stays as
  the fallback (R21.4).

---

## Correctness properties & testing

Following the repo's conventions (pure domain logic property-tested with jqwik; services unit-tested with
Mockito over repository interfaces — recording subclasses for concrete collaborators given Java 25; endpoint
role-guards covered by `EndpointRoleGuardIntegrationTest`; a full-context smoke test catches DI regressions):

- **Pure/properties:** pipeline stage-transition legality (replaces the `LeadStatus` table test); lead-import
  row parsing/validation; missed-follow-up determination (given activities since a reminder); target
  attainment math (week/month, salesperson/team); activity-timeline ordering/merge; permission resolution
  (grant precedence: user over role, default seed = today).
- **Services:** assign/bulk-assign scoping (SALESPERSON 403), tag add/remove on terminal leads, task/meeting
  lifecycle + reminders, lead ingestion de-dupe + assignment, WhatsApp client selection by config.
- **Guards:** every new endpoint's role/permission in the guard integration test; `ApplicationContextLoadsTest`
  boots the new beans (dual-constructor services get `@Autowired` on the primary ctor — the documented DI
  gotcha).
- Each phase: full backend `mvn clean test` + admin `build:admin` must be green before deploy.

## Migration summary (all additive; start at V83)

| Ver | Phase | Change |
|---|---|---|
| V83 | 1 | leads: creator/admin-marker/follow-up-time/assigned-at; `lead_tags` + `lead_tag_assignments` (+ seed 5 dispositions) |
| V84 | 2 | `crm_activities` |
| V85 | 2 | `tasks` |
| V86 | 2 | `meetings` |
| V87 | 2 | leads.`follow_up_missed_at` |
| V88 | 2 | extend `sales_targets` (week/team/conversion) |
| V89 | 3 | `pipelines` + `pipeline_stages`; leads.`pipeline_id`/`stage_id`; drop `ck_leads_status`; seed+backfill Default pipeline |
| V90 | 3 | `lead_sources` (seed from enum) |
| V91 | 3 | `crm_permissions` (+ safe-default seed) |
| V92 | 4 | lead-channel ingestion log / dedupe support (if needed) |

## Open decisions to confirm with the client (gate Phase 4 + a few Phase 1/3 choices)

1. **Team assignment rule (R1.3):** assign to the Team_Lead, or round-robin across team members? (Design
   supports both; default = assign to Team_Lead.)
2. **WhatsApp BSP (R21):** which provider (Meta Cloud API direct, Gupshup, WATI, Interakt…)? Drives API shape
   + cost + template-approval process.
3. **Lead channels (R20):** which are must-have first — Meta Lead Ads (IG/FB), Google/Gallbox, Website — and
   who owns the Meta App / page access tokens?
4. **Permissions scope (R19):** is per-user permission granularity needed now, or is per-role enough for v1?
   (Per-role is simpler; per-user is in the schema either way.)
5. **"Closed" vs "Converted":** confirm "Closed" maps to LOST/terminal and "Converted" to WON (today's model),
   or whether a distinct CLOSED terminal stage is wanted (Phase 3 makes this a config choice).
