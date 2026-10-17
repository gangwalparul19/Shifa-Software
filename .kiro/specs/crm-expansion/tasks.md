# Implementation Plan

Phase-ordered, incremental tasks. Each task is independently buildable, ships with tests, and must leave
the backend suite (`mvn clean test`) + admin build (`build:admin`) green. Migrations are additive
(start at **V83**); every new capability defaults to today's behavior until configured. Requirement refs
point at `requirements.md`.

> Deploy cadence: a phase may be deployed once its tasks are green. Phase 4 tasks are gated on the client
> supplying provider credentials (see the open decisions in `design.md`).

---

## Phase 1 — Lead ownership, bulk intake, tags & capture

- [ ] 1. Migration **V83** + entity extensions
  - Add `leads.created_by_user_id` (backfill = `owner_user_id`), `created_by_admin`, `follow_up_time`,
    `assigned_at`; create `lead_tags` (+ seed the 5 dispositions) and `lead_tag_assignments`; add indexes.
  - Extend `LeadEntity` with the new fields + getters/setters. Verify boot (`ddl-auto: validate`) locally.
  - _Requirements: 1.1, 3.1, 4.2, 6.1_

- [ ] 2. Lead assignment & reassignment
  - `LeadService.assign(id, toUserId, teamRule, actor)` + `bulkAssign(ids, toUserId, actor)`: update
    `owner_user_id` + `assigned_at`, keep `created_by_user_id`; authorize ADMIN/TEAM_LEAD only
    (SALESPERSON → 403); audit; notify new assignee via `StaffNotificationDispatcher.dispatchToUser`.
  - Endpoints `PUT /api/leads/{id}/assign`, `POST /api/leads/bulk-assign` (+ DTOs).
  - Tests: scoping (salesperson 403), reassignment keeps creator, bulk per-lead result, notification fired.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 15.1_

- [ ] 3. Lead tags & call dispositions
  - `LeadTag` definition entity/repo; `LeadTagAssignment` entity/repo; `LeadService.addTag/removeTag`
    (allowed on terminal leads too); `GET /api/leads/tags`; `POST/DELETE /api/leads/{id}/tags`.
  - Admin tag read now; full tag CRUD lands in Phase 3 (`/api/admin/lead-tags`).
  - Tests: add/remove (incl. terminal lead), idempotent assignment, audit recorded.
  - _Requirements: 4.1, 4.2, 4.4, 4.5, 5.1, 5.3_

- [ ] 4. "Lead by Admin" marker
  - Set `created_by_admin` on capture + bulk when actor is ADMIN; expose on `LeadResponse`/summary; list
    filter `?createdByAdmin=`.
  - Tests: admin capture flagged, salesperson capture not, filter works.
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 5. Follow-up date **+ time**
  - Extend `setFollowUp`/`edit` + `FollowUpRequest` with optional `followUpTime`; re-arm reminder;
    default time-of-day from config when null. Keep date-only back-compat.
  - Tests: set/update/clear with time, null→default, existing date-only leads unaffected.
  - _Requirements: 6.1, 6.2, 6.3_

- [ ] 6. Bulk lead upload (CSV/Excel)
  - `LeadBulkImportService` over `SpreadsheetParser`; column aliases (name/mobile/email/source/note/
    assignee/tags/followUp); per-row validate→create; cap `app.crm.lead-import.max-rows`; return
    `LeadImportResult{created, skipped[]}`. Endpoint `POST /api/admin/leads/import` (multipart, ADMIN).
  - Mark imported leads `created_by_admin=true`.
  - Tests: valid+invalid rows mixed, cap enforced, assignee resolution, tags applied, non-admin 403.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1_

- [ ] 7. Frontend — leads list/drawer/capture
  - List: "Lead by Admin" badge, tag chips, filters (assignee / tag / admin-created), bulk-select +
    "Assign N" (ADMIN/TEAM_LEAD). Drawer: tag add/remove (incl. terminal), follow-up date+time picker,
    Assign/Reassign control. Capture form: pincode→city/state autofill via `PincodeService`. Admin
    "Import leads" action (file → result summary, mirror remittance-import UX).
  - _Requirements: 1, 2, 3, 4, 5.1, 6, 7_

- [ ] 8. Phase 1 verification + deploy
  - `mvn clean test` + `build:admin` green; boot-validate V83; deploy to prod via the standard flow;
    verify live (service active, Flyway applied V83, HTTPS 200, new endpoints 401 unauth).

---

## Phase 2 — Activity model, monitoring & performance

- [ ] 9. Migration **V84** + `activity` package
  - `crm_activities` table; `ActivityEntry` entity/repo; `ActivityService.record / timelineForLead /
    timelineForCustomer`. Add activity writers to the Phase-1 lead touch points (stage/tag/assign/
    follow-up) — the audit calls added in Phase 1 now also emit an activity.
  - `POST /api/activities/log` (actor-scoped) for frontend CALL/WHATSAPP activities.
  - Tests: timeline ordering, lead vs customer keying, record on each touch.
  - _Requirements: 11.1, 11.2, 11.3_

- [ ] 10. Tasks (**V85**)
  - `task` package (entity/repo/service/controller): create/update/complete, scoped (own; TEAM_LEAD/ADMIN
    team/all); `TaskReminderJob` (mirror `FollowUpReminderJob`) → owner notification, idempotent.
  - Endpoints `GET/POST /api/tasks`, `PUT /api/tasks/{id}`, `POST /api/tasks/{id}/complete`.
  - Tests: lifecycle, scoping, due-reminder idempotency, activity on create/complete.
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 15.2_

- [ ] 11. Meetings (**V86**)
  - `meeting` package: schedule/reschedule/cancel/record-outcome, scoped like tasks; `MeetingReminderJob`
    for upcoming meetings → owner notification.
  - Tests: lifecycle, reminder, activity, scoping.
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 15.2_

- [ ] 12. Missed follow-ups (**V87**)
  - Add `leads.follow_up_missed_at`; `MissedFollowUpJob` runs after `app.crm.followup.grace-hours`: for a
    due lead with no `crm_activities` since the reminder, stamp missed, record `FOLLOW_UP_MISSED` activity,
    notify assignee, escalate to Team_Lead/Admin. "Missed list" endpoint + admin cross-user follow-up view.
  - Tests (pure): missed-determination given activities-since-reminder; service: stamp+notify+escalate.
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 15.2_

- [ ] 13. Weekly & team targets (**V88**)
  - Extend `sales_targets` (`period_type`, `team_lead_id`, `conversion_target`); relax unique key;
    extend `SalesTargetService` for week/team + conversion goal; keep monthly per-salesperson intact.
  - Weekly performance view data for a user/team.
  - Tests (pure): attainment math for week/month, salesperson/team; monthly back-compat.
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 17 (perf view), B17_

- [ ] 14. Reports — by stage / user / team
  - Extend `LeadReportAggregator` + `LeadService` reports (by-stage, by-user, by-team) reusing the
    existing report shapes; role-scoped. Frontend: add to the Reports/CRM views.
  - Tests (pure): grouping exactness + scoping.
  - _Requirements: 13.1, 13.2, 13.3_

- [ ] 15. Unified CRM cockpit + notifications wiring
  - `GET /api/admin/crm/overview` fans out to existing services (pipeline, follow-up health incl. missed,
    performance/targets, sales/customer headline). New `crm-dashboard` admin view linking to details.
  - Notification `type` constants for new-lead-assigned / task-due / meeting-upcoming / follow-up-missed
    (dispatched in tasks 2/10/11/12); frontend bell + activity timeline on lead & customer drawers.
  - Tests: overview composition; guard coverage for the new endpoints.
  - _Requirements: 11.4, 14.1, 14.2, 14.3, 15.1, 15.2, 15.3_

- [ ] 16. Phase 2 verification + deploy (as task 8).

---

## Phase 3 — Configurable pipelines, stages, tags & permissions

- [ ] 17. Migration **V89** — pipelines & stages + data migration
  - `pipelines` + `pipeline_stages`; `leads.pipeline_id`/`stage_id`; **drop `ck_leads_status`**; seed a
    "Default" pipeline reproducing New→Contacted→Quoted (+ terminal Won/Lost) and backfill every lead's
    pipeline/stage from `status`; keep `status` populated (mapped from stage) for history/back-compat.
  - Boot-validate; verify every existing lead got a pipeline+stage and history is intact.
  - _Requirements: 16.3, 17.3_

- [ ] 18. Pipeline configuration service + stage transitions
  - `pipeline` package: `Pipeline`/`PipelineStage` entities, `PipelineService` (CRUD, reorder, entry/
    terminal designation, activate/deactivate), `PipelineController` (`/api/admin/pipelines`, ADMIN).
  - Move transition legality from the `LeadStatus` enum table to stage config; compatibility shim maps the
    enum values onto the Default pipeline's stages so unconverted call sites keep working. Re-open = a
    configured terminal→active transition (audited) (satisfies R5.2).
  - Tests (pure): stage-transition legality replaces the old `LeadStatus` property test; default seed
    reproduces today's funnel; re-open allowed only when configured.
  - _Requirements: 16.1, 16.2, 17.1, 17.2, 5.2_

- [ ] 19. Lead-source management (**V90**) + stage-scoped/custom tags + CRM settings
  - `lead_sources` table seeded from the enum; capture/upload/reporting read it (enum stays as default).
  - Full `lead_tags` admin CRUD (`/api/admin/lead-tags`) + optional stage association; CRM settings
    surface (follow-up default time, grace window, default pipeline, default assignment rule).
  - Frontend: admin **CRM settings** area (pipelines, stages, tags, sources, defaults).
  - Tests: source/tag management + enforcement; settings round-trip.
  - _Requirements: 4.3, 18.1, 18.2, 18.3, 24 (settings)_

- [ ] 20. Configurable permissions (**V91**)
  - `permission` package: `crm_permissions` (+ safe-default seed = today's behavior), `PermissionService`
    + SpEL helper `@perms.has(...)`; apply on assign/import/manage-pipelines/manage-tags/view-all-reports
    endpoints in addition to the base role. Effective-permissions endpoint for the frontend to hide
    unavailable actions.
  - Tests (pure): grant resolution (user over role; default seed = today); guard: endpoints honor perms.
  - _Requirements: 19.1, 19.2, 19.3, 3 (A3 permissions)_

- [ ] 21. Phase 3 verification + deploy (as task 8). Extra care: the V89 data migration is the riskiest —
  rehearse on a DB copy; confirm lead counts/history preserved pre/post.

---

## Phase 4 — External integrations (gated on client credentials)

- [ ] 22. Lead-channel ingestion framework
  - `leadchannel` package + `LeadIngestionService.ingest(channel, payload)`: map→lead with correct source,
    de-dupe (mobile/email window), apply assignment rule, record activity, outbox-retry on failure. Each
    channel config-gated (no-op when creds unset).
  - Tests: de-dupe, assignment rule, outbox retry on failure, disabled-channel no-op.
  - _Requirements: 20.2, 20.3, 20.4_

- [ ] 23. Channel connectors (per the client's priority)
  - `POST /api/webhooks/meta/leads` (Meta Lead Ads — IG+FB; `X-Hub-Signature-256` + subscribe challenge),
    `POST /api/webhooks/google-leads` (Google/Gallbox), `POST /api/webhooks/website-leads` (shared-secret).
    Follow the courier/Shopify webhook pattern (permitAll + verify).
  - Tests: signature verify (accept/reject), challenge handshake, payload→ingest.
  - _Requirements: 20.1_

- [ ] 24. Real WhatsApp provider
  - `HttpWhatsAppClient implements WhatsAppClient` `@ConditionalOnProperty(app.whatsapp.mode=LIVE)` (mock
    stays default); wire BSP send-template/send-session + `WhatsAppProperties`. Existing outbox drainer +
    `NotificationMatrix` flow unchanged. Delivery/inbound callbacks (if supported) → verified webhook →
    `WHATSAPP` activity. Click-to-chat stays as fallback.
  - Tests: client selection by config (mock vs live), send mapping, callback→activity.
  - _Requirements: 21.1, 21.2, 21.3, 21.4_

- [ ] 25. Phase 4 verification + deploy (per channel, as credentials arrive).

---

## Cross-cutting (every task)
- Dual-constructor services (Clock/test variant) mark the **primary ctor `@Autowired`** (documented DI
  gotcha); `ApplicationContextLoadsTest` must still boot.
- New endpoints added to `EndpointRoleGuardIntegrationTest`.
- `@PreAuthorize` lists updated for any new role/permission a shell-reachable role hits on login.
- UI additions are mobile-first and reuse shared components; all timestamps via `IstDatePipe`.
