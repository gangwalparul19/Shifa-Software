## Requirements Document

## Introduction

This feature expands the existing Shifa OMS CRM to meet the client's CRM proposal. It builds directly
on the shipped **Lead Management** (`lead-management` spec), **Customer 360** (`crm` module),
**WhatsApp templates** (`whatsapp` module), **Salesperson 360 / sales targets** (`performance` module),
**Teams** (`auth` team management), and the **notification** (in-app bell + SSE + config-gated Web Push)
and **audit** infrastructure already in production.

It reuses the existing stack and conventions throughout: `SalespersonScopeResolver` for role scoping,
the transactional **outbox** + `StaffNotificationDispatcher` for notifications, `AuditService` for the
system-activity trail, additive Flyway migrations (never editing an applied migration), and the
mobile-first PWA UI language. The "User Mobile Application" requirements in the proposal are served by
the **salesperson view of the existing responsive PWA** — no separate native app is in scope.

**Explicitly out of scope (parked at the client's request, to be taken later):**
- A separate **native mobile application** (the PWA covers the mobile experience).
- **Cloud-based call recording** / telephony integration (click-to-call via `tel:` stays).

The work is organized into four phases so it can be delivered incrementally; requirements are grouped
to match. Phases 1–3 reuse existing infrastructure; Phase 4 depends on third-party provider accounts
(a WhatsApp Business Solution Provider, Meta/Google lead channels) and is scoped here but gated on the
client supplying credentials and commercial sign-off.

## Glossary

- **OMS / System**: The Shifa OMS backend + admin PWA acting together.
- **Lead**: A prospective sale captured before it becomes an order.
- **Lead_Owner / Assignee**: The CRM user currently responsible for a lead (today the `owner_user_id`).
- **CRM_User**: Any staff user who works leads — primarily `SALESPERSON` and `TEAM_LEAD`; `ADMIN` oversees.
- **Pipeline**: A named, ordered set of stages a lead moves through. Today there is ONE fixed pipeline.
- **Pipeline_Stage**: A configurable step within a Pipeline (replaces today's fixed `LeadStatus` enum stages).
- **Lead_Tag**: A label applied to a lead (e.g. a call disposition: Not Pick, DND, Busy, Wrong Number, Call Back).
- **Follow_Up**: A scheduled next-contact for a lead, with a date and (new) a time.
- **Missed_Follow_Up**: A Follow_Up that became due, was notified, and received no action within a grace window.
- **Task**: A to-do owned by a CRM_User, optionally linked to a lead/customer, with a due date and completion state.
- **Meeting**: A scheduled customer meeting owned by a CRM_User, with a start time and an outcome.
- **Activity**: Any recorded touch on a lead/customer — stage change, tag, note, task, meeting, follow-up, call, message.
- **Activity_Timeline**: The chronological, per-lead and per-customer feed of Activities.
- **Target**: A revenue/conversion goal set for a CRM_User or team, for a week or a month.
- **Lead_Channel**: An external source that can automatically create leads (Instagram, Facebook, Google/Gallbox, Website).
- **WhatsApp_Provider**: A WhatsApp Business API provider (BSP) that can send/receive messages programmatically.
- **Lead_Service / Task_Service / Meeting_Service / Pipeline_Service / etc.**: existing/new backend modules.

---

# Phase 1 — Lead ownership, bulk intake, tags & capture improvements

Self-contained, high-value, no external dependencies.

### Requirement 1: Assign and reassign leads

**User Story:** As an Admin, I want to assign and reassign leads to users (or a team), so that work is distributed and no lead is orphaned.

#### Acceptance Criteria

1. THE System SHALL represent a lead's current responsible user as an assignee (the existing `owner_user_id`), and SHALL retain the original creator separately so reassignment never loses who created the lead.
2. WHEN an Admin assigns or reassigns a lead to a CRM_User, THE Lead_Service SHALL update the assignee and SHALL record the change (from-user, to-user, actor, timestamp) as an Activity and an audit event.
3. WHEN an Admin assigns a lead to a team (a Team_Lead), THE Lead_Service SHALL set the assignee per the team's rule (to the Team_Lead, or round-robin to a team member — the chosen rule is specified in design) and SHALL record it.
4. THE Lead_Service SHALL allow bulk reassignment of multiple selected leads in a single action and SHALL report per-lead success/failure.
5. WHERE the caller is a SALESPERSON, THE System SHALL NOT permit reassigning a lead to another user (assignment is an Admin/Team_Lead capability); a SALESPERSON request to reassign SHALL be rejected with 403.
6. WHEN a lead is reassigned to a different CRM_User, THE System SHALL notify the new assignee (in-app, and Web Push when enabled).

### Requirement 2: Bulk upload leads via Excel/CSV

**User Story:** As an Admin, I want to upload many leads from an Excel/CSV file, so that externally-collected leads enter the CRM quickly.

#### Acceptance Criteria

1. WHEN an Admin uploads a `.csv`, `.xlsx`, or `.xls` file of leads, THE System SHALL parse it with the shared spreadsheet parser and validate each row (name required; mobile 10 digits when present; source resolvable; optional email/note/assignee/tags/follow-up).
2. THE System SHALL create one lead per valid row and SHALL return a per-row result (created vs skipped-with-reason), creating valid rows even when some rows are invalid.
3. THE System SHALL support an optional "assign to" column (by username or user id); WHERE absent, uploaded leads SHALL be assignable in bulk after upload.
4. THE System SHALL cap a single upload at a configured maximum row count and reject an oversized file with a clear error.
5. THE bulk upload SHALL be restricted to ADMIN (and optionally TEAM_LEAD per design); a non-authorized caller SHALL get 403.

### Requirement 3: Identify admin-uploaded leads ("Lead by Admin")

**User Story:** As an Admin, I want leads I create/upload to be marked as "Lead by Admin", so that admin-sourced leads are distinguishable from user-captured leads.

#### Acceptance Criteria

1. WHEN a lead is created by an ADMIN (single capture or bulk upload), THE System SHALL mark it as admin-created and SHALL retain this marker permanently.
2. THE System SHALL surface a "Lead by Admin" badge wherever leads are listed and on the lead detail, for both Admin and the assignee.
3. THE System SHALL allow filtering the lead list by admin-created vs user-created.

### Requirement 4: Lead tags and call dispositions

**User Story:** As a CRM_User, I want to tag a lead with call dispositions and other labels, so that I can record outcomes like Not Pick, DND, Busy, Wrong Number, and Call Back.

#### Acceptance Criteria

1. THE System SHALL support applying zero or more Lead_Tags to a lead, independent of the lead's Pipeline_Stage.
2. THE System SHALL ship a managed set of default call-disposition tags: **Not Pick, DND, Busy, Wrong Number, Call Back**.
3. THE System SHALL allow an Admin to create, rename, and retire custom Lead_Tags, and (per design) optionally scope a tag to specific Pipeline_Stages.
4. WHEN a CRM_User adds or removes a Lead_Tag, THE System SHALL record it as an Activity (actor, tag, timestamp).
5. THE System SHALL display a lead's tags wherever leads are listed and on the lead detail, and SHALL allow filtering the list by tag.

### Requirement 5: Update status and tags after a lead is Closed/Converted

**User Story:** As a CRM_User, I want to still update tags (and limited status) on a lead after it is Closed or Converted, so that late information (e.g. DND, Wrong Number, a re-open) is captured.

#### Acceptance Criteria

1. THE System SHALL allow adding/removing Lead_Tags on a terminal (WON/LOST) lead, recording each as an Activity.
2. THE System SHALL allow an authorized transition out of a terminal state only through an explicit, audited "re-open" action (the allowed transitions are defined in the Pipeline configuration, Phase 3); absent re-open, terminal leads remain immutable for stage changes.
3. THE System SHALL continue to retain and display the full stage/status/Activity history of a lead after it is Closed or Converted.

### Requirement 6: Follow-up date and time

**User Story:** As a CRM_User, I want to set a follow-up with a specific date AND time, so that reminders fire at the right moment.

#### Acceptance Criteria

1. THE System SHALL store a Follow_Up as a date and an optional time (defaulting to a configured time-of-day when no time is given), preserving today's date-only behavior for existing leads.
2. THE System SHALL allow a CRM_User to set, update, and reschedule the Follow_Up date and time on a non-terminal lead.
3. WHEN a Follow_Up date/time is changed, THE System SHALL re-arm the reminder for the new moment.

### Requirement 7: Auto-capture City and State from Pincode in lead capture

**User Story:** As a CRM_User, I want City and State to auto-fill from a Pincode while capturing a lead, so that I enter less data.

#### Acceptance Criteria

1. WHEN a CRM_User enters a valid 6-digit Indian Pincode in the lead capture form, THE System SHALL best-effort auto-fill the empty City and State fields using the existing Pincode lookup.
2. THE auto-fill SHALL be non-blocking (never fails capture) and SHALL leave manually-entered City/State untouched.

---

# Phase 2 — Activity model, monitoring & performance

Tasks, meetings, the missed-follow-up lifecycle, the unified activity timeline, weekly targets/performance, team-wise reports, and the unified CRM cockpit — all on existing notification/audit rails.

### Requirement 8: Tasks

**User Story:** As a CRM_User, I want to create, update, and complete tasks, so that I can manage my own to-dos linked to leads/customers.

#### Acceptance Criteria

1. THE Task_Service SHALL let a CRM_User create a Task with a title, optional description, optional due date/time, optional priority, and an optional link to a lead or customer.
2. THE Task_Service SHALL let the owner update a Task and mark it complete (recording completion time), and SHALL record each change as an Activity.
3. THE System SHALL scope Tasks so a CRM_User sees their own Tasks; an Admin (and a Team_Lead for their team) SHALL see their users' Tasks (read + monitor).
4. WHEN a Task becomes due, THE System SHALL notify the owner (in-app + Web Push when enabled).
5. THE System SHALL provide the owner a list of their open, due, and overdue Tasks.

### Requirement 9: Meetings

**User Story:** As a CRM_User, I want to schedule and manage customer meetings, so that planned customer interactions are tracked.

#### Acceptance Criteria

1. THE Meeting_Service SHALL let a CRM_User schedule a Meeting with a customer/lead reference, a title, a start date/time, an optional location/mode, and notes.
2. THE Meeting_Service SHALL let the owner reschedule, cancel, and record an outcome for a Meeting, recording each as an Activity.
3. WHEN a Meeting is upcoming, THE System SHALL notify the owner (in-app + Web Push when enabled).
4. THE System SHALL scope Meetings like Tasks (own for a CRM_User; team/all for Team_Lead/Admin).

### Requirement 10: Missed follow-ups and escalation

**User Story:** As an Admin, I want follow-ups that were notified but not acted on to move to a Missed list, so that dropped leads are visible and can be recovered.

#### Acceptance Criteria

1. THE System SHALL treat a Follow_Up as due at its date/time and notify the assignee, as today.
2. IF a due Follow_Up receives no action (no stage change, note, tag, task, or reschedule on that lead) within a configured grace window after notification, THEN THE System SHALL mark it Missed_Follow_Up.
3. THE System SHALL provide the assignee a Missed list and SHALL provide an Admin a cross-user view of upcoming, due/overdue, and missed follow-ups.
4. WHEN a Follow_Up becomes Missed, THE System SHALL notify the assignee (and, per design, escalate to their Team_Lead/Admin).

### Requirement 11: Unified activity timeline (lead and customer)

**User Story:** As an Admin or CRM_User, I want a single chronological activity timeline per lead and per customer with date and time, so that I can see the complete history at a glance.

#### Acceptance Criteria

1. THE System SHALL record Activities for: lead stage changes, tag add/remove, notes, assignment changes, follow-up set/reschedule/missed, tasks (created/completed), meetings (scheduled/outcome), and customer-directed calls/WhatsApp sends initiated from the CRM.
2. THE System SHALL expose a per-lead Activity_Timeline and a per-customer Activity_Timeline, each ordered newest-first with full date and time (IST).
3. THE System SHALL retain the Activity_Timeline after a lead is Closed/Converted.
4. THE per-customer timeline SHALL aggregate across all that customer's leads and orders (keyed by mobile, matching Customer 360's model).

### Requirement 12: Weekly targets and weekly performance

**User Story:** As an Admin, I want to set weekly (and monthly) targets for users and teams and see weekly performance, so that I can manage short-horizon goals.

#### Acceptance Criteria

1. THE System SHALL let an Admin set a Target for a CRM_User or a team, for a week or a month, as a revenue and/or conversion goal.
2. THE System SHALL compute achievement against each active Target (achieved vs target, % progress, pace).
3. THE System SHALL show a CRM_User their own weekly and monthly performance vs Target.
4. THE System SHALL extend the existing monthly target capability without breaking it (monthly targets already shipped).

### Requirement 13: Source / stage / user / team reports

**User Story:** As an Admin, I want source-wise, stage-wise, user-wise, and team-wise lead reports, so that I can analyze the funnel from every angle.

#### Acceptance Criteria

1. THE System SHALL provide lead reports grouped by source, by stage, by user (assignee), and by team, over an optional date window.
2. THE user-wise and team-wise reports SHALL include lead counts, conversion rate, and the revenue of converted leads.
3. THE reports SHALL be role-scoped (an Admin sees all; a Team_Lead sees their team; a SALESPERSON sees their own).

### Requirement 14: Unified CRM cockpit (admin dashboard)

**User Story:** As an Admin, I want one dashboard that blends leads, customers, sales, user performance, and target-vs-achievement, so that I can monitor the whole CRM in one place.

#### Acceptance Criteria

1. THE System SHALL present an Admin CRM dashboard combining: lead pipeline summary, follow-up health (upcoming/due/missed), conversion and performance by user/team, target-vs-achievement, and sales/customer headline metrics.
2. THE dashboard SHALL link each summary to its detailed view (leads, reports, follow-ups, targets).
3. THE dashboard SHALL reuse existing dashboard/report data sources where available (no duplicate computations that could drift).

### Requirement 15: Notifications for new leads, tasks, meetings, follow-ups

**User Story:** As a CRM_User, I want notifications for new leads assigned to me, upcoming tasks, meetings, and follow-ups, so that I never miss an action.

#### Acceptance Criteria

1. WHEN a lead is newly assigned to a CRM_User, THE System SHALL notify that user (in-app + Web Push when enabled).
2. THE System SHALL notify the owner for upcoming/due Tasks, upcoming Meetings, and due/missed Follow_Ups.
3. THE notifications SHALL use the existing bell + live SSE + config-gated Web Push rails (no duplicate notification system).

---

# Phase 3 — Configurable pipelines, stages, tags & permissions

The configuration layer that turns today's fixed enums into admin-managed data.

### Requirement 16: Multiple configurable pipelines

**User Story:** As an Admin, I want to create multiple sales pipelines for different business needs, so that different lead types follow different processes.

#### Acceptance Criteria

1. THE System SHALL let an Admin create, rename, activate/deactivate, and order multiple Pipelines.
2. THE System SHALL assign each lead to exactly one Pipeline (defaulting to a designated default Pipeline), and SHALL allow moving a lead between Pipelines per rules defined in design.
3. THE System SHALL preserve all existing leads by migrating the current fixed funnel into a seeded "Default" Pipeline so no lead/history is lost.

### Requirement 17: Customizable pipeline stages

**User Story:** As an Admin, I want to customize the stages of a pipeline, so that the pipeline matches our actual process.

#### Acceptance Criteria

1. THE System SHALL let an Admin add, rename, reorder, and retire Pipeline_Stages within a Pipeline, and SHALL designate which stages are terminal (won/lost/closed) and which are the entry stage.
2. THE System SHALL enforce stage transitions according to the Pipeline's configuration (replacing the hard-coded enum transition table), rejecting illegal transitions.
3. THE System SHALL keep historical leads consistent: a retired stage SHALL remain valid for leads/history already in it (retire ≠ delete).

### Requirement 18: Stage-scoped and custom tags, and CRM settings management

**User Story:** As an Admin, I want to manage lead sources, stages, tags, and other CRM settings, so that I control the CRM's vocabulary.

#### Acceptance Criteria

1. THE System SHALL let an Admin manage Lead_Tags (create/rename/retire) and optionally associate tags with specific Pipeline_Stages (building on Requirement 4).
2. THE System SHALL let an Admin manage the available lead sources (add/rename/retire) used across capture, upload, and reporting.
3. THE System SHALL centralize CRM configuration (pipelines, stages, tags, sources, follow-up defaults, grace windows) in an Admin "CRM settings" area.

### Requirement 19: Configurable roles and permissions

**User Story:** As an Admin, I want to manage user roles and permissions, so that access matches each user's responsibilities beyond the fixed roles.

#### Acceptance Criteria

1. THE System SHALL let an Admin grant/revoke a defined set of CRM permissions (e.g. assign leads, bulk upload, manage pipelines, manage tags, view all reports) to roles or individual users, layered on top of the existing fixed base roles.
2. THE System SHALL enforce these permissions server-side on the corresponding endpoints (defense-in-depth; the UI also hides unavailable actions).
3. THE System SHALL ship safe defaults that reproduce today's behavior, so enabling the permission layer changes nothing until an Admin customizes it.

---

# Phase 4 — External integrations (provider-dependent)

Scoped here but gated on the client providing provider accounts/credentials and commercial sign-off. Each integration is independently deliverable.

### Requirement 20: Automatic lead ingestion from external channels

**User Story:** As an Admin, I want leads to flow automatically from Instagram, Facebook, Google/Gallbox, and our Website, so that no enquiry is manually re-keyed.

#### Acceptance Criteria

1. THE System SHALL provide a secure inbound webhook/connector per Lead_Channel (Meta Lead Ads for Instagram/Facebook, Google/Gallbox, and a Website form) that verifies authenticity (signature/token) and ingests leads automatically.
2. WHEN a channel delivers a lead, THE System SHALL create a lead tagged with the correct source, de-duplicate against existing leads (by mobile/email within a window), and route it per the configured assignment rule.
3. THE System SHALL record each ingestion as an Activity and SHALL surface ingestion failures to an Admin without dropping data (retry/outbox, like the existing courier/Shopify webhooks).
4. THE System SHALL treat each channel as independently enable-able and SHALL no-op safely when a channel's credentials are not configured.

### Requirement 21: WhatsApp Business API integration

**User Story:** As a CRM_User, I want WhatsApp integrated so messages send programmatically and templates deliver reliably, so that customer communication is first-class (beyond click-to-chat).

#### Acceptance Criteria

1. THE System SHALL integrate a WhatsApp_Provider behind the existing `WhatsAppClient` abstraction (replacing the mock), sending template and session messages via the provider's API, config-gated so the mock remains the default until credentials are set.
2. THE System SHALL send the existing lifecycle WhatsApp notifications (NotificationMatrix) and admin-authored templates through the provider, recording send + delivery status as Activities.
3. WHERE the provider supports inbound messages/status callbacks, THE System SHALL receive them via a verified webhook and record them on the lead/customer Activity_Timeline.
4. THE System SHALL preserve click-to-chat (`wa.me`) as a fallback and SHALL never block a user action on provider availability.

---

## Non-functional and safety requirements

1. **Additive migrations only** — every schema change is a new Flyway version; no applied migration is edited; existing leads/orders/history are preserved (notably the Phase 3 migration of the fixed funnel into a Default pipeline).
2. **Role scoping preserved** — all new reads/writes respect `SalespersonScopeResolver` (own/team/all) and enforce authorization server-side.
3. **Reuse, don't duplicate** — notifications via the existing outbox + dispatcher + Web Push; audit via `AuditService`; reports via the existing reporting/aggregation layer where possible; UI via the existing mobile-first components.
4. **Backward compatibility** — existing endpoints/behaviors (single lead capture, monthly targets, lifecycle notifications, mock WhatsApp) keep working; new capabilities default to today's behavior until configured.
5. **External integrations fail safe** — a missing/invalid provider credential disables that integration without affecting the rest of the CRM.
6. **Tests** — each phase ships with unit/property tests following the repo's patterns (pure domain logic property-tested; services unit-tested; endpoint role-guards covered), and the full backend suite + admin build must pass before deploy.
