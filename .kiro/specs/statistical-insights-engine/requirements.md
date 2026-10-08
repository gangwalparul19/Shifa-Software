# Requirements: Statistical Insights Engine

## Introduction

The **Statistical Insights Engine** is Phase-1 of the AI-Based Insights roadmap
(`docs/Sales-Enablement-and-AI-Insights.md`, sections B2/B3 + C). It turns the data the OMS already
captures — orders, status history, line items, stock movements, courier records, order returns,
receivables, and leads — into **actionable insights** the admin (and, scoped, the salesperson) sees on
the dashboard: sales anomalies, low-stock reorder suggestions, RTO/delivery-failure risk, courier
performance scorecards, return-rate anomalies, COD-outstanding build-up, and lead-source conversion.

It is deliberately **statistical only** — pure Java arithmetic over pre-aggregated numbers, run as a
nightly batch that writes a compact `insights` table the dashboard reads instantly. **No external calls,
no LLM, zero free-tier cost.** The narrative/NL layer (`AiInsightProvider`) is a later phase and is out
of scope here; the design must not preclude adding it, but must not depend on it.

The engine reuses the established patterns: a pure aggregator (like `ReportAggregator`/
`LeadReportAggregator`) exercised by jqwik property tests, a `@Scheduled` job mirroring
`FollowUpReminderJob` (dual-constructor `Clock`, idempotent per run), the transactional outbox +
`StaffNotificationDispatcher` for surfacing high-severity insights to admins, `AuditService` for the
run trail, and role-shaped `RoleDashboardSummary` extension. Migration is the next unused version **V26**.

## Requirements

### Requirement 1 — Nightly insight computation

**User Story:** As an admin, I want the system to compute insights automatically every night, so that
each morning I see an up-to-date picture without running anything myself.

#### Acceptance Criteria
1. WHEN the configured nightly schedule (`app.insights.nightly.cron`, default `0 0 2 * * *`) fires THEN the system SHALL compute all insight types for the current date and persist them to the `insights` table.
2. WHEN a run computes insights for a date that already has computed insights THEN the system SHALL replace that date's insights (recompute is idempotent — a second run for the same date yields the same set, with no duplicates).
3. WHEN computing an individual insight type fails THEN the system SHALL log and skip it and continue the remaining types, so one failure never aborts the whole run.
4. WHEN a run completes THEN the system SHALL record one audit event (`INSIGHTS_COMPUTED`, entity `INSIGHT`) summarizing how many insights were produced.
5. WHEN the run computes insights THEN the computation window and "today" SHALL be derived from an injected `Clock` (deterministic in tests).

### Requirement 2 — On-demand recompute

**User Story:** As an admin, I want to trigger a recompute on demand, so that I can refresh insights during a demo or after correcting data without waiting for the nightly run.

#### Acceptance Criteria
1. WHEN an admin calls `POST /api/insights/recompute` THEN the system SHALL run the same computation as the nightly job for the current date and return the number of insights produced.
2. WHEN a non-admin calls the recompute endpoint THEN the system SHALL reject it with 403.

### Requirement 3 — Sales anomaly detection

**User Story:** As an admin, I want to be alerted when sales deviate sharply from the recent norm, so that I can react to dips or capitalize on spikes.

#### Acceptance Criteria
1. WHEN the current period's total sales differ from the preceding equal-length period by more than the configured percentage threshold (`app.insights.sales-anomaly-pct`, default 30) THEN the system SHALL produce a `SALES_ANOMALY` insight (GLOBAL scope) describing the direction (dip/spike) and the percentage change.
2. WHEN the change is within the threshold THEN the system SHALL NOT produce a `SALES_ANOMALY` insight.
3. WHEN the preceding period had zero sales and the current period had non-zero sales THEN the system SHALL treat it as a spike (no divide-by-zero).
4. Revenue SHALL exclude `REJECTED`/`CANCELLED` orders (matching the P&L revenue definition).

### Requirement 4 — Low-stock reorder suggestions

**User Story:** As an admin, I want reorder suggestions with a stock-out ETA, so that I restock before running out.

#### Acceptance Criteria
1. WHEN a product's projected days-of-cover (on-hand ÷ average daily consumption over the configured lookback `app.insights.reorder-lookback-days`, default 30) is below the configured cover threshold (`app.insights.reorder-cover-days`, default 14) THEN the system SHALL produce a `LOW_STOCK_REORDER` insight (PRODUCT scope) with a suggested reorder quantity and the stock-out ETA in days.
2. The suggested reorder quantity SHALL be non-negative and cover at least the configured target cover days of average consumption.
3. WHEN a product has no consumption (SALE movements) over the lookback window THEN the system SHALL NOT produce a reorder insight for it (no divide-by-zero, no false alarm).
4. Consumption SHALL be derived from `stock_movements` of type `SALE` within the lookback window; on-hand from the latest `balance_after`.

### Requirement 5 — RTO / delivery-failure risk

**User Story:** As an admin, I want risky in-flight orders flagged before dispatch, so that I can confirm them and reduce failed deliveries.

#### Acceptance Criteria
1. WHEN an open (not-yet-terminal, not-yet-delivered) order has a computed risk score at or above the configured threshold (`app.insights.rto-risk-threshold`, default 60 on a 0–100 scale) THEN the system SHALL produce an `RTO_RISK` insight (ORDER scope) with the score and the contributing factors.
2. The risk score SHALL rise monotonically with the risk factors (high COD amount, delivery-failure-prone destination state from history, prior failed attempts on the customer) and stay within `[0, 100]`.
3. WHEN an order is terminal or already delivered THEN the system SHALL NOT produce an `RTO_RISK` insight for it.

### Requirement 6 — Courier performance scorecards

**User Story:** As an admin, I want a per-courier scorecard, so that I can allocate volume to the better performers.

#### Acceptance Criteria
1. WHEN a run computes THEN the system SHALL produce one `COURIER_SCORECARD` insight (COURIER scope) per courier company that has shipments in the window, reporting delivery success %, RTO %, and average transit days.
2. All reported percentages SHALL be within `[0, 100]`, and the delivered / RTO / failed counts SHALL partition the courier's terminal shipments.
3. WHEN a courier's RTO % exceeds the configured threshold (`app.insights.courier-rto-warn-pct`, default 15) THEN its scorecard SHALL carry `WARNING` severity, otherwise `INFO`.

### Requirement 7 — Return-rate & COD-outstanding anomalies

**User Story:** As an admin, I want to be warned about rising returns and mounting unsettled COD, so that I catch quality and cash-flow problems early.

#### Acceptance Criteria
1. WHEN the return rate (returns ÷ delivered orders) in the current window exceeds the configured threshold (`app.insights.return-rate-warn-pct`, default 10) THEN the system SHALL produce a `RETURN_RATE_ANOMALY` insight (GLOBAL scope).
2. WHEN total unsettled COD receivables exceed the configured amount (`app.insights.cod-outstanding-warn`, default 50000) THEN the system SHALL produce a `COD_OUTSTANDING_BUILDUP` insight (GLOBAL scope) with the outstanding total.

### Requirement 8 — Lead-source conversion insight

**User Story:** As an admin, I want to see which lead channels convert best and worst, so that I focus effort where it pays off.

#### Acceptance Criteria
1. WHEN a run computes AND leads exist THEN the system SHALL produce a `LEAD_SOURCE_CONVERSION` insight (GLOBAL scope) naming the best- and worst-converting lead sources with their conversion rates (`won ÷ leads`).
2. WHEN no leads exist THEN the system SHALL NOT produce a lead-source conversion insight.

### Requirement 9 — Reading, filtering, and dismissing insights

**User Story:** As an admin, I want to view, filter, and dismiss insights, so that my dashboard stays relevant.

#### Acceptance Criteria
1. WHEN an admin calls `GET /api/insights` THEN the system SHALL return the latest (most recent computed date) non-dismissed insights, newest first, optionally filtered by `type`, `scope`, `severity`, and an `includeDismissed` flag.
2. WHEN a salesperson calls `GET /api/insights` THEN the system SHALL return only insights scoped to them (`SALESPERSON` scope with their user id) plus their own `RTO_RISK` insights on orders they created; it SHALL NOT return operation-wide admin insights.
3. WHEN an admin calls `POST /api/insights/{id}/dismiss` THEN the system SHALL mark that insight dismissed (idempotent) and exclude it from the default listing thereafter.
4. WHEN a non-authorized role (e.g. PACKING_USER) calls any insights endpoint THEN the system SHALL reject it with 403.

### Requirement 10 — Surface high-severity insights to admins

**User Story:** As an admin, I want the important insights pushed to my notifications, so that I don't have to go looking.

#### Acceptance Criteria
1. WHEN a run produces an insight of `WARNING` or `DANGER` severity THEN the system SHALL enqueue a transactional-outbox event and dispatch one in-app notification to the `ADMIN` role, de-duplicated so re-running the same date does not double-notify.
2. WHEN a run produces only `INFO` insights THEN the system SHALL NOT push notifications.

### Requirement 11 — Dashboard integration

**User Story:** As an admin, I want an insights summary on my dashboard, so that I get the headline at a glance.

#### Acceptance Criteria
1. WHEN an admin loads `GET /api/dashboard/summary` THEN the payload SHALL include an insights section with counts by severity and the top few current insights (title + severity + type).
2. The salesperson dashboard SHALL be unaffected except where salesperson-scoped insights exist (out of scope for this phase to add a widget; the admin section is the deliverable).

### Requirement 12 — Data & migration safety

**User Story:** As an operator, I want the new table added safely, so that existing data and migrations are untouched.

#### Acceptance Criteria
1. The `insights` table SHALL be added by a new additive migration **V26** that is safe on the seeded V22 dataset; no applied migration is edited.
2. Insight computation SHALL be read-only against all existing tables (it only writes the `insights` table, outbox rows, notifications, and audit events).

### Requirement 13 — Frontend insights view

**User Story:** As an admin on mobile, I want a clean insights screen, so that I can act on the findings.

#### Acceptance Criteria
1. WHEN an admin opens the Insights screen THEN the system SHALL show insights as mobile-first cards grouped/colored by severity, with type + title + detail + metric, a dismiss action, and a manual "Recompute" button.
2. The screen SHALL be reachable from the admin hamburger nav and follow the existing Tabler + Shifa-green + severity-pill styling; the admin dashboard SHALL show an insights summary tile linking to the screen.
