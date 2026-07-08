# Implementation Plan: Lead Management & Sales Pipeline

## Overview

Incremental build of the `com.shifa.oms.lead` module + mobile-first UI, per `requirements.md` and
`design.md`. Backend domain first (enums + migration + entities → state machine → service → endpoints
→ reminders/reports), then frontend, with tests alongside. Property-based tests (jqwik) implement the
9 Correctness Properties from the design; each PBT sub-task runs ≥100 iterations, is tagged
`Feature: lead-management, Property {n}: {text}`, exercises pure logic in-memory, and respects the
Java 25 gotcha (real instances / recording subclasses — never Mockito mocks of concrete classes).
Migration is the next unused version **V25** (never edit an applied migration).

## Tasks

- [x] 1. Data model, enums, and migration
  - [x] 1.1 Add `LeadStatus` enum + transition table
    - `NEW/CONTACTED/QUOTED/WON/LOST`; frozen table (§4); `canTransitionTo`, `allowedTargets`, `isTerminal`; WON not a manual target
    - _Requirements: 2.1, 2.2, 2.4, 2.5_ · _Design: §State Machine_
  - [x] 1.2 Add `LostReason` enum
    - `PRICE, OUT_OF_STOCK, NO_RESPONSE, DUPLICATE, NOT_INTERESTED, OTHER`
    - _Requirements: 2.3_ · _Design: §3.1_
  - [x] 1.3 Add migration `V25__leads.sql`
    - `leads` + `lead_status_history` tables + indexes per §3.4; additive; confirm V25 is next unused
    - _Requirements: 7.1_ · _Design: §3.2, §3.3, §3.4_
  - [x] 1.4 Add `LeadEntity` + `LeadStatusHistory` JPA entities
    - Map exactly to V25 columns (reuse `order.LeadSource`); getters/setters in existing style
    - _Requirements: 7.1, 7.3_ · _Design: §3.2, §3.3_
  - [x]* 1.5 Migration + mapping smoke test
    - V25 applies on the seeded dataset; Hibernate `validate` passes against the new columns
    - _Requirements: 7.1_ · _Design: §Testing_

- [x] 2. LeadService core (capture, transition, follow-up) + scoping/audit/history
  - [x] 2.1 Repositories + scoped finders
    - `LeadRepository` (+ `findAllScoped(ownerUserId)`, pipeline counts, due-follow-up finder, search) and `LeadStatusHistoryRepository`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 5.2_ · _Design: §Components_
  - [x] 2.2 `LeadService.capture` + validation
    - Require name + in-set source (OTHER note ≤200), optional mobile(10-digit)/email/note/followUp; owner=actor; status NEW; one creation history row; audit
    - _Requirements: 1.1–1.6, 2.1_ · _Design: §Components_
  - [x]* 2.3 PBT Property 6 (capture validation) — _Validates: 1.1, 1.2, 1.6, 2.1_
  - [x] 2.4 `LeadService.transition` (advance / mark LOST)
    - Legality via `LeadStatus` (409 illegal/terminal); LOST requires `LostReason` (persisted); one history row + audit; WON not manually settable
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 7.5_ · _Design: §State Machine, §Components_
  - [x]* 2.5 PBT Property 1 (transition legality) — _Validates: 2.2, 2.4, 2.7_
  - [x]* 2.6 PBT Property 2 (WON unreachable manually) — _Validates: 2.5, 4.2_
  - [x]* 2.7 PBT Property 3 (LOST requires reason) — _Validates: 2.3, 2.4_
  - [x]* 2.8 PBT Property 4 (one history row per transition) — _Validates: 2.6, 7.5_
  - [x] 2.9 `setFollowUp` + scoped list/detail/pipeline/dueFollowUps
    - Set/clear follow-up; scoped list/detail (404 out-of-scope); pipeline counts; due = non-terminal & follow_up_date ≤ today
    - _Requirements: 3.1–3.6, 5.1, 5.2, 5.4_ · _Design: §Components_
  - [x]* 2.10 PBT Property 5 (salesperson scoping) — _Validates: 3.1, 3.2, 6.5, 7.2_
  - [x]* 2.11 PBT Property 8 (due-follow-ups membership) — _Validates: 5.2, 5.4_

- [x] 3. Convert-to-order
  - [x] 3.1 `LeadService.convert(id, orderRequest, actor)`
    - Reject terminal (409); force customer + leadSource from lead; call `OrderService.createSalespersonOrder`; set lead WON + `converted_order_id`; history + audit; rollback leaves lead unchanged on order failure
    - _Requirements: 4.1–4.6, 7.3_ · _Design: §Convert Flow_
  - [x]* 3.2 PBT Property 7 (convert marks WON/links/preserves source; failure no-op) — _Validates: 4.2, 4.3, 4.4, 4.5, 7.3_

- [x] 4. REST API + role guards
  - [x] 4.1 `LeadController` (`/api/leads`)
    - capture / list / pipeline / detail / status / follow-up / edit / due-follow-ups / convert per §API; `@PreAuthorize("hasAnyRole('SALESPERSON','ADMIN')")`; scoping applied in service
    - _Requirements: 3.6, 4.1, 5.1, 7.2_ · _Design: §API_
  - [x]* 4.2 Endpoint role-guard integration test (SALESPERSON/ADMIN 2xx, others 403; convert links a WON order) — _Requirements: 7.2_

- [x] 5. Follow-up reminders
  - [x] 5.1 `FollowUpReminderJob` (@Scheduled)
    - Find due non-terminal leads not yet reminded today → enqueue in-app reminder to the owner via outbox + `StaffNotificationDispatcher`; idempotent per due-day (`reminded_on` marker or dedup key)
    - _Requirements: 5.3, 5.5_ · _Design: §Follow-up Reminders_

- [x] 6. Reporting + dashboard integration
  - [x] 6.1 `LeadReportAggregator` (pure) + report endpoints
    - by-source / conversion (won/leads per source & owner) / pipeline / lost-reasons; scoped for salesperson
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_ · _Design: §Reporting_
  - [x]* 6.2 PBT Property 9 (conversion-rate + groupings exact) — _Validates: 6.1, 6.2, 6.3, 6.4_
  - [x] 6.3 Extend `GET /api/dashboard/summary`
    - SALESPERSON payload gains pipeline-by-stage counts + due-follow-up count; ADMIN gains a leads/conversion summary
    - _Requirements: 6.6_ · _Design: §Frontend, §API_

- [x] 7. Checkpoint — backend green
  - Full backend `mvn test` passes; ask the user if questions arise.

- [x] 8. Frontend (mobile-first leads)
  - [x] 8.1 Leads service + models (`leads/`)
    - Angular service over `/api/leads` + DTO models
    - _Requirements: 8.1_ · _Design: §Frontend_
  - [x] 8.2 Pipeline/list + capture form
    - Mobile-first cards grouped by status + counts, status pills; capture form (name/source/mobile/email/note/follow-up) with validation
    - _Requirements: 8.1, 8.2, 8.4, 1.x_ · _Design: §Frontend_
  - [x] 8.3 Lead detail + advance-status + set follow-up + Convert
    - Status history, advance action, follow-up date, Convert → New Order pre-filled then convert endpoint
    - _Requirements: 4.1, 2.x, 5.1, 8.2_ · _Design: §Frontend, §Convert Flow_
  - [x] 8.4 Due follow-ups view + nav + dashboard widgets
    - Due list; add Leads to hamburger for SALESPERSON+ADMIN; salesperson dashboard pipeline counts + due-follow-up badge
    - _Requirements: 5.2, 6.6, 8.3_ · _Design: §Frontend_
  - [x]* 8.5 Component checks at 360px (pipeline cards, capture form, convert)
    - _Requirements: 8.1, 8.2_ · _Design: §Testing_

- [x] 9. Final checkpoint — full build & tests
  - Backend `mvn test` green + admin `build:admin` completes; ask the user if questions arise.

## Notes
- `*` sub-tasks are optional tests; core sub-tasks are never optional. All 9 design properties are covered by PBT tasks.
- Migration V25 is additive; no applied migration is edited; safe on the seeded V22 dataset.
- Reuses existing scoping/audit/outbox/order-creation; no changes to removed modules.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["1.4", "1.5"] },
    { "id": 2, "tasks": ["2.1"] },
    { "id": 3, "tasks": ["2.2", "2.4", "2.9"] },
    { "id": 4, "tasks": ["2.3", "2.5", "2.6", "2.7", "2.8", "2.10", "2.11", "3.1", "6.1"] },
    { "id": 5, "tasks": ["3.2", "4.1", "5.1", "6.2", "6.3"] },
    { "id": 6, "tasks": ["4.2", "7"] },
    { "id": 7, "tasks": ["8.1"] },
    { "id": 8, "tasks": ["8.2", "8.3", "8.4"] },
    { "id": 9, "tasks": ["8.5", "9"] }
  ]
}
```
