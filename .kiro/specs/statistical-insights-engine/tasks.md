# Implementation Plan: Statistical Insights Engine

## Overview

Incremental build of the `com.shifa.oms.insights` module + mobile-first admin UI, per `requirements.md`
and `design.md`. Pure domain first (enums + `Insight`/`InsightThresholds`/projections + `InsightEngine`),
then migration + entity + repository, then the computation service + nightly job + notifications + audit,
then the read API + dashboard integration, then frontend. Property-based tests (jqwik) implement the 8
Correctness Properties from the design; each PBT sub-task runs ≥100 iterations, is tagged
`Feature: statistical-insights-engine, Property {n}: {text}`, exercises the pure `InsightEngine`
in-memory, and respects the Java 25 gotcha (no Mockito on concrete classes). Migration is the next unused
version **V26** (never edit an applied migration).

## Tasks

- [x] 1. Pure domain: enums, value types, and the engine
  - [x] 1.1 Add `insights/domain` enums
    - `InsightType` (7 types + `from(String)` parser), `InsightScope` (GLOBAL/PRODUCT/COURIER/SALESPERSON/ORDER), `InsightSeverity` (INFO/WARNING/DANGER + `toNotificationSeverity()` → `AdminNotification.SEVERITY_*`, + `isNotifiable()`)
    - _Requirements: 3.1, 4.1, 5.1, 6.1, 7.1, 8.1, 10.1_ · _Design: §Pure domain_
  - [x] 1.2 Add `Insight`, `InsightThresholds`, and projection records
    - `Insight` (with natural-key accessor), `InsightThresholds` (+ `defaults()`), projections `SalesWindow`, `ProductConsumption`, `CourierOutcome`, `OpenOrderRisk`, `ReturnStats`, `CodOutstanding`, `LeadSourceConversion`, and `InsightInputs` bundle
    - _Requirements: 3–8_ · _Design: §Pure domain_
  - [x] 1.3 Implement `InsightEngine` (pure)
    - One method per family (sales anomaly, reorder, RTO risk, courier scorecard, return-rate, COD build-up, lead-source conversion) + `compute(InsightInputs, InsightThresholds, LocalDate)`; deterministic ordering; scoring/reorder formulas per design; GLOBAL rows use `scopeRefId=0` sentinel
    - _Requirements: 3.1–3.4, 4.1–4.4, 5.1–5.3, 6.1–6.3, 7.1–7.2, 8.1–8.2_ · _Design: §Pure domain, §Scoring detail, §Reorder detail_
  - [x]* 1.4 PBT Property 1 (sales anomaly iff over threshold) — _Validates: 3.1, 3.2, 3.3_
  - [x]* 1.5 PBT Property 2 (reorder correctness) — _Validates: 4.1, 4.2, 4.3_
  - [x]* 1.6 PBT Property 3 (RTO score bounds & monotonicity) — _Validates: 5.1, 5.2, 5.3_
  - [x]* 1.7 PBT Property 4 (courier scorecard integrity) — _Validates: 6.1, 6.2, 6.3_
  - [x]* 1.8 PBT Property 5 (return-rate & COD thresholds) — _Validates: 7.1, 7.2_
  - [x]* 1.9 PBT Property 6 (lead-source conversion) — _Validates: 8.1, 8.2_
  - [x]* 1.10 PBT Property 7 (determinism/idempotency) — _Validates: 1.2_
  - [x]* 1.11 PBT Property 8 (severity mapping total) — _Validates: 10.1, 10.2_

- [x] 2. Migration, entity, repository
  - [x] 2.1 Add migration `V26__insights.sql`
    - `insights` table + indexes + unique natural key per §Data Model; additive; confirm V26 is next unused
    - _Requirements: 12.1_ · _Design: §Data Model_
  - [x] 2.2 Add `InsightEntity` + `InsightRepository`
    - Entity maps V26 columns (enums STRING, `created_at` DB-default), `from(Insight)` (sentinel `scopeRefId=0` for GLOBAL), `markDismissed(userId, at)`; repo finders: `findMaxComputedDate`, `deleteByComputedDate`, `findByComputedDate...`, scoped finder, natural-key finder
    - _Requirements: 1.2, 9.1, 9.3, 12.2_ · _Design: §Persistence_
  - [x]* 2.3 Migration + mapping smoke test
    - V26 applies on the seeded dataset; Hibernate `validate` passes (mirror `MigrationV25SchemaSmokeTest`)
    - _Requirements: 12.1_ · _Design: §Testing_

- [x] 3. Computation service, nightly job, notifications, audit
  - [x] 3.1 Add `app.insights.*` config + `AuditActions` constants + `OutboxEvent` constant
    - `application.yml` block per §Config; add `INSIGHTS_COMPUTED` verb + `ENTITY_INSIGHT`; add `EVENT_INSIGHT_ALERT` (+ reuse `AGGREGATE_SYSTEM`) on `OutboxEvent`; add repo finders on `StockMovementRepository` (`findByMovementTypeAndCreatedAtBetween`, `findTopByProductIdOrderByCreatedAtDescIdDesc`) and any courier finder needed
    - _Requirements: 1.1, 1.4, 10.1_ · _Design: §Services, §Reuse_
  - [x] 3.2 Implement `InsightComputationService.computeForToday()`
    - Gather (read-only) → build projections → `engine.compute(...)` → `deleteByComputedDate(today)` + `saveAll` → for notifiable insights publish outbox `INSIGHT_ALERT` + `dispatchToRole(ADMIN)` (de-dup via event id) → `AuditService.record(null,"SYSTEM",INSIGHTS_COMPUTED,...)`; dual-ctor `Clock`; per-type failures logged & skipped
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 3–8, 10.1, 10.2, 12.2_ · _Design: §Services_
  - [x] 3.3 Add `InsightNightlyJob` (@Scheduled)
    - `@Scheduled(cron="${app.insights.nightly.cron:0 0 2 * * *}")` delegating to `computeForToday()`; never lets an exception escape; dual-ctor `Clock`
    - _Requirements: 1.1_ · _Design: §Services_
  - [x]* 3.4 Computation-service idempotency + notification test
    - Fixed `Clock` + small dataset: same-date rerun replaces (no duplicates); a WARNING/DANGER insight yields exactly one ADMIN notification (de-duped on rerun)
    - _Requirements: 1.2, 10.1_ · _Design: §Testing_

- [x] 4. Read API + role scoping + dashboard
  - [x] 4.1 Add `InsightService` (read) + DTOs
    - `list(filters, principal)` latest-date, role-scoped (ADMIN all; SALESPERSON only their `SALESPERSON`-scope + own `RTO_RISK` orders); `dismiss(id, principal)` ADMIN idempotent; `InsightResponse`/`RecomputeResponse` DTOs
    - _Requirements: 9.1, 9.2, 9.3_ · _Design: §Services, §API_
  - [x] 4.2 Add `InsightController` (`/api/insights`)
    - GET list (`type`/`scope`/`severity`/`includeDismissed`), POST `/{id}/dismiss` (ADMIN), POST `/recompute` (ADMIN); class `@PreAuthorize hasAnyRole('ADMIN','SALESPERSON')`, method-level ADMIN on dismiss/recompute
    - _Requirements: 2.1, 2.2, 9.1, 9.2, 9.3, 9.4_ · _Design: §API_
  - [x]* 4.3 Endpoint role-guard integration test
    - ADMIN 2xx list/recompute/dismiss; SALESPERSON 2xx list (scoped rows only), 403 recompute/dismiss; PACKING_USER 403 all (mirror `LeadEndpointRoleGuardIntegrationTest`)
    - _Requirements: 2.2, 9.2, 9.4_ · _Design: §Testing_
  - [x] 4.4 Extend `GET /api/dashboard/summary` (admin insights section)
    - Add `Insights` sub-record to `RoleDashboardSummary.Admin` (counts by severity + top headlines); populate in `RoleDashboardService.admin` from latest computed date (empty when none)
    - _Requirements: 11.1, 11.2_ · _Design: §Dashboard integration_

- [x] 5. Checkpoint — backend green
  - Full backend `mvn test` passes (487 tests, BUILD SUCCESS); ask the user if questions arise.

- [x] 6. Frontend (mobile-first insights)
  - [x] 6.1 Insights service + models (`insights/`)
    - `InsightsService` over `/api/insights` (list/dismiss/recompute) + `insights.model.ts` (Insight DTO, severity pill + type-label helpers)
    - _Requirements: 13.1_ · _Design: §Frontend_
  - [x] 6.2 `InsightsComponent` (cards + dismiss + recompute)
    - Mobile-first cards grouped/colored by severity, type+title+detail+metric, dismiss action, "Recompute" button, empty state; route `/insights` (ADMIN guard) + hamburger nav entry
    - _Requirements: 13.1, 13.2_ · _Design: §Frontend_
  - [x] 6.3 Admin dashboard insights tile
    - Add an insights summary tile (counts by severity) to the admin dashboard linking to `/insights`, sourced from the extended `dashboard.model.ts`
    - _Requirements: 11.1, 13.2_ · _Design: §Frontend_
  - [x]* 6.4 Component check at 360px (cards, dismiss, recompute)
    - _Requirements: 13.1_ · _Design: §Testing_

- [x] 7. Final checkpoint — full build & tests
  - Backend `mvn test` green + admin `build:admin` completes; ask the user if questions arise.

## Notes
- `*` sub-tasks are optional tests; core sub-tasks are never optional. All 8 design properties are covered by PBT tasks.
- Migration V26 is additive; no applied migration is edited; safe on the seeded V22 dataset.
- Reuses existing reporting/DateRange, scheduling (`@EnableScheduling` on `Application`), outbox/notification, audit, dashboard, and lead conversion helpers; statistical only (no LLM / AiInsightProvider this phase).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3"] },
    { "id": 2, "tasks": ["1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "1.10", "1.11", "2.1"] },
    { "id": 3, "tasks": ["2.2"] },
    { "id": 4, "tasks": ["2.3", "3.1"] },
    { "id": 5, "tasks": ["3.2"] },
    { "id": 6, "tasks": ["3.3", "3.4", "4.1"] },
    { "id": 7, "tasks": ["4.2", "4.4"] },
    { "id": 8, "tasks": ["4.3", "5"] },
    { "id": 9, "tasks": ["6.1"] },
    { "id": 10, "tasks": ["6.2", "6.3"] },
    { "id": 11, "tasks": ["6.4", "7"] }
  ]
}
```
