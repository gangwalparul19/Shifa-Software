# Design: Statistical Insights Engine

## Overview

A new backend module `com.shifa.oms.insights` computes statistical insights nightly (and on demand) and
persists them to an `insights` table the dashboard reads instantly. The heavy lifting lives in a **pure
`InsightEngine`** (no persistence/web deps) exercised directly by jqwik property tests — mirroring
`reporting.domain.ReportAggregator` and `lead.LeadReportAggregator`. A thin `InsightComputationService`
gathers data from existing repositories into pure projection records, runs the engine, and persists the
result idempotently per computed date; an `InsightNightlyJob` (`@Scheduled`, dual-ctor `Clock`) and an
admin recompute endpoint both drive it. High-severity insights fan out to admins via the transactional
outbox + `StaffNotificationDispatcher`. The admin dashboard and a new mobile-first Angular screen surface
the results.

**Non-goals (this phase):** no LLM / `AiInsightProvider`, no NL querying, no forecasting beyond a moving
average. Design leaves room for those but does not depend on them.

## Architecture

```
[InsightNightlyJob @Scheduled]     [POST /api/insights/recompute (ADMIN)]
                \                    /
                 v                  v
          InsightComputationService.computeForToday(clock)
             | 1. gather (read-only) via existing repositories
             |    orders, line_items, status_history, stock_movements,
             |    courier_records, order_returns, receivables, leads
             | 2. build pure projections (insights/domain/*Record)
             | 3. InsightEngine.compute(projections, thresholds, today)  <-- PURE, PBT target
             | 4. persist idempotently: delete existing for `today`, insert fresh
             | 5. for WARNING/DANGER -> outbox event + StaffNotificationDispatcher.dispatchToRole(ADMIN)
             | 6. AuditService.record(SYSTEM, INSIGHTS_COMPUTED, ...)
             v
        insights table  <---- InsightService (read: list/scope/dismiss) ----> InsightController /api/insights
                        <---- RoleDashboardService.admin() insights section
```

## Components

### Pure domain (`insights/domain/`) — no Spring, no JPA

- **`InsightType`** enum: `SALES_ANOMALY, LOW_STOCK_REORDER, RTO_RISK, COURIER_SCORECARD,
  RETURN_RATE_ANOMALY, COD_OUTSTANDING_BUILDUP, LEAD_SOURCE_CONVERSION` (+ `from(String)` parser like
  `ReportType`).
- **`InsightScope`** enum: `GLOBAL, PRODUCT, COURIER, SALESPERSON, ORDER`.
- **`InsightSeverity`** enum: `INFO, WARNING, DANGER` with `toNotificationSeverity()` mapping to the
  `AdminNotification.SEVERITY_*` strings (`INFO→info`, `WARNING→warning`, `DANGER→danger`).
- **`Insight`** record — a computed insight (immutable, persistence-free):
  `(InsightType type, InsightScope scope, Long scopeRefId, String scopeLabel, InsightSeverity severity,
  String title, String detail, BigDecimal metricValue, LocalDate computedDate)`. A stable **natural key**
  is `(type, scope, scopeRefId, computedDate)` — used for idempotent persistence and notification de-dup.
- **`InsightThresholds`** record — all tunables with defaults:
  `(BigDecimal salesAnomalyPct=30, int reorderLookbackDays=30, int reorderCoverDays=14,
  int rtoRiskThreshold=60, BigDecimal courierRtoWarnPct=15, BigDecimal returnRateWarnPct=10,
  BigDecimal codOutstandingWarn=50000)`. Built from `app.insights.*` config; a `defaults()` factory for tests.
- **Projection records** (built by the service, consumed by the engine):
  - `SalesWindow(BigDecimal currentTotal, BigDecimal previousTotal)` — pre-summed revenue for the current
    and preceding equal-length windows (exclude REJECTED/CANCELLED).
  - `ProductConsumption(Long productId, String productName, int onHand, long unitsSoldInWindow, int lookbackDays)`.
  - `CourierOutcome(Long courierCompanyId, String courierName, long delivered, long rto, long failed,
    long otherTerminal, double avgTransitDays)` — counts over the courier's terminal shipments.
  - `OpenOrderRisk(Long orderId, String orderCode, BigDecimal codAmount, String state, int priorFailedForCustomer,
    double stateFailureRate)` — features for the RTO score.
  - `ReturnStats(long delivered, long returns)` and `CodOutstanding(BigDecimal unsettledTotal)`.
  - `LeadSourceConversion(LeadSource source, long leads, long won)` (reuse `LeadReportAggregator.conversionRate`).
- **`InsightEngine`** (pure): one method per family returning `List<Insight>`, plus a top-level
  `compute(InsightInputs inputs, InsightThresholds t, LocalDate today)` that concatenates them. Each
  method is deterministic and side-effect-free. `InsightInputs` is a record bundling all the projection
  collections so the engine signature stays stable as inputs grow.

**Scoring detail (RTO risk, `[0,100]`, monotonic):**
`score = clamp( wState*stateFailureRate*100 + wCod*codFactor + wPrior*min(priorFailed,3)/3*100 )`
with fixed weights summing to 1 (e.g. state 0.5, cod 0.3, prior 0.2); `codFactor` = `min(cod/ codCap,1)`
with `codCap` a constant (e.g. 3000). Higher any factor ⇒ higher score (monotonic), capped at 100.

**Reorder detail:** `avgDaily = unitsSoldInWindow / lookbackDays`; if `avgDaily == 0` skip. `coverDays =
onHand / avgDaily`; flag when `coverDays < reorderCoverDays`. `suggestedQty = ceil(avgDaily *
reorderCoverDays) - onHand`, floored at 0. ETA days = `floor(coverDays)`.

### Persistence

- **`InsightEntity`** (`@Entity @Table(name="insights")`) mapping the V26 columns; enums via
  `@Enumerated(STRING)`; `created_at` DB-default (`insertable=false`); `dismissed` boolean; `dismissed_at`,
  `dismissed_by` nullable. A `from(Insight)` factory and a `toInsight()` / DTO projection.
- **`InsightRepository extends JpaRepository<InsightEntity,Long>`**:
  - `List<InsightEntity> findByComputedDateOrderBySeverityAscIdDesc(LocalDate)` (or fetch + sort in service),
  - `Optional<LocalDate> findMaxComputedDate()` via `@Query("select max(i.computedDate) ...")`,
  - `void deleteByComputedDate(LocalDate)` (idempotent recompute),
  - `List<InsightEntity> findByComputedDateAndDismissedFalse(LocalDate)`,
  - scoped finder for salesperson: `findByComputedDateAndScopeAndScopeRefId(...)`,
  - `Optional<InsightEntity> findByTypeAndScopeAndScopeRefIdAndComputedDate(...)` for notify de-dup if needed.

### Services

- **`InsightComputationService`** (`@Service`, dual-ctor `Clock`): `@Transactional int computeForToday()`.
  Steps: gather → project → `engine.compute(...)` → `repository.deleteByComputedDate(today)` →
  `saveAll` → notify high-severity → audit. Data gathering uses existing repos:
  - Sales: `OrderRepository.findByCreatedAtBetween` for current + previous windows (default window =
    trailing 7 days), sum `totalAmount` excluding REJECTED/CANCELLED.
  - Reorder: needs per-product on-hand + windowed SALE units. Add `StockMovementRepository`
    finders: `findByMovementTypeAndCreatedAtBetween(SALE, from, to)` and reuse latest `balance_after`
    per product (`findTopByProductIdOrderByCreatedAtDescIdDesc`). Product names via `ProductRepository`.
  - Courier: add `CourierRecordRepository.findByLastCourierStatusIsNotNull()` or gather all records +
    join order status; compute transit days from `status_history` (DISPATCHED→DELIVERED) where available,
    else from `estimated_delivery`. Courier names via existing courier company repo/settings.
  - RTO: open orders = `OrderRepository.findByOrderStatusInOrderByCreatedAtDesc(open set)`; state failure
    rate from historical terminal orders grouped by state; prior failed per customer via
    `countByCustomerMobile`-style aggregation over failed statuses.
  - Returns: `OrderReturnRepository` count in window vs delivered count. COD: `ReceivableRepository`
    unsettled COD total (reuse the dashboard's approach).
  - Leads: `LeadRepository.findAll()` → `LeadSourceConversion` per source.
- **`InsightNightlyJob`** (`@Component`, dual-ctor `Clock`):
  `@Scheduled(cron="${app.insights.nightly.cron:0 0 2 * * *}") @Transactional` → delegates to
  `computeForToday()`; best-effort, never lets an exception escape the scheduler thread.
- **`InsightService`** (`@Service`): read side — `list(filters, principal)` returns latest-date insights
  role-scoped (ADMIN: all; SALESPERSON: `SALESPERSON`-scope with their id + own `RTO_RISK` orders),
  `dismiss(id, principal)` (ADMIN). DTO mapping to `InsightResponse`.

### API (`InsightController`, `/api/insights`)

`@PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")` at class level; `recompute` and `dismiss` further
restricted to ADMIN via method `@PreAuthorize`.

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/insights` | ADMIN, SALESPERSON | list latest insights (`type`,`scope`,`severity`,`includeDismissed` optional) — role-scoped |
| POST | `/api/insights/{id}/dismiss` | ADMIN | dismiss one insight (idempotent) |
| POST | `/api/insights/recompute` | ADMIN | run computation now, return count |

DTOs: `InsightResponse(id, type, scope, scopeRefId, scopeLabel, severity, title, detail, metricValue,
computedDate, dismissed)`; `RecomputeResponse(int computed, LocalDate computedDate)`.

### Dashboard integration

Extend `RoleDashboardSummary.Admin` with an `Insights` sub-record
`(Map<String,Long> countsBySeverity, List<InsightHeadline> top)` where `InsightHeadline(type, severity,
title)`. `RoleDashboardService.admin(...)` injects `InsightService` and populates it from the latest
computed date (empty maps/list when none). Mirrors how `Leads` was threaded in.

### Frontend (`insights/`)

Angular feature mirroring `leads/`: `insights.service.ts` (`GET /api/insights`, dismiss, recompute),
`insights.model.ts` (Insight DTO + severity pill helpers + type labels), `InsightsComponent`
(mobile-first cards grouped by severity, colored pills, dismiss button, FAB/button "Recompute", empty
state). Route `/insights` guarded for ADMIN (reuse existing admin guard); add to hamburger nav under a
new "Insights" entry. Admin dashboard gains an insights summary tile (counts by severity) linking to `/insights`.

## Data Model (migration V26)

```sql
CREATE TABLE insights (
  id BIGINT NOT NULL AUTO_INCREMENT,
  insight_type VARCHAR(40) NOT NULL,
  scope VARCHAR(20) NOT NULL,
  scope_ref_id BIGINT NULL,
  scope_label VARCHAR(200) NULL,
  severity VARCHAR(20) NOT NULL,
  title VARCHAR(200) NOT NULL,
  detail TEXT NULL,
  metric_value DECIMAL(18,4) NULL,
  computed_date DATE NOT NULL,
  dismissed BOOLEAN NOT NULL DEFAULT FALSE,
  dismissed_at DATETIME NULL,
  dismissed_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_insights PRIMARY KEY (id),
  CONSTRAINT ck_insights_scope CHECK (scope IN ('GLOBAL','PRODUCT','COURIER','SALESPERSON','ORDER')),
  CONSTRAINT ck_insights_severity CHECK (severity IN ('INFO','WARNING','DANGER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_insights_computed_date ON insights (computed_date);
CREATE INDEX ix_insights_type_date ON insights (insight_type, computed_date);
CREATE INDEX ix_insights_scope ON insights (scope, scope_ref_id);
CREATE UNIQUE INDEX ux_insights_natural ON insights (insight_type, scope, scope_ref_id, computed_date);
```
Additive only; no FKs to keep it robust against scope_ref_id pointing at different entity kinds. Safe on
seeded V22. (Note: MySQL treats multiple NULLs in a unique index as distinct, which is fine — GLOBAL-scope
rows use a sentinel `scope_ref_id = 0` set by the entity `from(Insight)` factory to keep the natural key
unique per type+date.)

## Config (`application.yml`)

```yaml
app:
  insights:
    nightly:
      cron: "0 0 2 * * *"      # daily 02:00
    window-days: 7             # current/previous comparison window
    sales-anomaly-pct: 30
    reorder-lookback-days: 30
    reorder-cover-days: 14
    rto-risk-threshold: 60
    courier-rto-warn-pct: 15
    return-rate-warn-pct: 10
    cod-outstanding-warn: 50000
```
Bind via a `@ConfigurationProperties("app.insights")` record or `@Value` fields in the computation
service; convert to an `InsightThresholds` before calling the engine.

## Correctness Properties (jqwik, exercise the pure `InsightEngine`)

1. **Sales anomaly iff over threshold.** For generated (current, previous, pct): an insight is produced
   ⇔ `|pctChange| > threshold` (with previous=0 & current>0 ⇒ spike); direction matches the sign.
2. **Reorder correctness.** For generated consumption: flagged ⇔ `avgDaily>0 ∧ coverDays<coverThreshold`;
   when flagged `suggestedQty ≥ 0` and `onHand + suggestedQty ≥ ceil(avgDaily*coverDays)`; never flagged
   when `avgDaily==0`.
3. **RTO score bounds & monotonicity.** Score ∈ `[0,100]`; increasing any single risk factor never
   decreases the score; flagged ⇔ `score ≥ threshold`; terminal/delivered orders never flagged.
4. **Courier scorecard integrity.** All percentages ∈ `[0,100]`; delivered+rto+failed+other equals the
   courier's terminal shipment count; severity is WARNING ⇔ `rtoPct > courierRtoWarnPct`.
5. **Return-rate & COD thresholds.** Return-rate anomaly ⇔ `rate > returnRateWarnPct` (rate=0 when
   delivered=0, never flagged); COD build-up ⇔ `unsettled > codOutstandingWarn`.
6. **Lead-source conversion.** Best/worst picked by exact `won/leads` rate with deterministic tie-break;
   no insight when there are no leads; per-source rate equals `LeadReportAggregator.conversionRate`.
7. **Determinism / idempotency.** `compute(inputs, t, date)` called twice on equal inputs yields an equal
   `List<Insight>` (same order, same natural keys) — this underpins persistence idempotency.
8. **Severity mapping total.** Every `InsightSeverity` maps to a non-null `AdminNotification.SEVERITY_*`
   string, and only WARNING/DANGER are "notifiable".

## Testing

- PBT (jqwik) for the 8 properties above, ≥100 iterations each, tagged `Feature: statistical-insights-engine`,
  pure engine only (Java 25 gotcha: no Mockito on concrete classes — the engine needs none).
- A migration/mapping smoke test that V26 applies and `InsightEntity` validates (mirror
  `MigrationV25SchemaSmokeTest`).
- A `@SpringBootTest` role-guard test: ADMIN 2xx on list/recompute/dismiss; SALESPERSON 2xx on list only
  and sees scoped rows; PACKING_USER 403 (mirror `LeadEndpointRoleGuardIntegrationTest`).
- A computation-service test with a fixed `Clock` and small in-memory-ish dataset asserting idempotent
  recompute (same-date rerun replaces, no duplicates) and that a high-severity insight yields a notification.

## Reuse / conventions

- Pure aggregator + PBT: `reporting.domain.ReportAggregator`, `lead.LeadReportAggregator`,
  `reporting.domain.DateRange` (reused for windowing).
- Scheduled job + `Clock`: `lead.FollowUpReminderJob`; `@EnableScheduling` already on `Application`.
- Outbox + notify: `OutboxEventPublisher.publish(AGGREGATE_SYSTEM, id, "INSIGHT_ALERT", payload)` (add
  `EVENT_INSIGHT_ALERT` constant on `OutboxEvent`) + `StaffNotificationDispatcher.dispatchToRole(...,
  Role.ADMIN)` with `sourceEventId = event.getId()` for de-dup.
- Audit: add `INSIGHTS_COMPUTED` verb + `ENTITY_INSIGHT` to `AuditActions`; use the explicit-actor
  `AuditService.record(null, "SYSTEM", ...)` since the nightly job has no principal.
- Dashboard: extend `RoleDashboardSummary.Admin` + `RoleDashboardService.admin`.
- Migration is next unused **V26**; never edit an applied migration.
