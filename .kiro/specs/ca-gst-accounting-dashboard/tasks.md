# Implementation Plan

## Overview

Add a **CA** role and a GST/accounting dashboard + filing-ready outward GST report, computed from existing
order line tax snapshots and the seller's GST settings. Reqs 1–8 need no schema change (CA is an enum
value; figures derive from existing data). A pure `GstEngine` produces rate/HSN/state summaries + a
GSTR-3B-style net; a read-only service adds in/out money aggregates; exports reuse the existing pipeline.
Req 9 (capture GST on purchases/expenses for automatic ITC) is a separable later batch.

## Tasks

- [ ] 1. Pure GST engine + tests (backend)
  - Add `gst/domain`: `SupplyType`, `GstLine`, `GstOrder`, `TaxSplit`, `RateWiseRow`, `HsnRow`,
    `StateWiseRow`, `Gstr3bSummary`, `GstComputation`, and `GstEngine.splitLine` / `compute` (GST-inclusive
    extraction mirroring `OrderPricing`; intra vs inter split; rate/HSN/state aggregation + 3B summary).
  - Unit + jqwik property tests for Correctness Properties 1–5 (extraction bound, split integrity,
    cross-summary reconciliation, exclusion, period/rounding).
  - _Requirements: 3.1, 3.2, 3.3, 3.5, 4.1, 4.2, 4.3, 4.5, 5.1_

- [ ] 2. CA role + authorisation (backend)
  - Add `Role.CA`; include in `STAFF_ROLES`. Add `'CA'` to `@PreAuthorize` on shared finance/report
    endpoints (reports, finance/P&L, expenses, procurement, returns, reconciliation, order read) alongside
    `'ACCOUNTANT'`. Ensure CA cannot reach approval/fulfilment/mutation endpoints.
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 8.1_

- [ ] 3. GST accounting service (backend)
  - `gst/GstAccountingService` (@Service, read-only tx, Clock dual-ctor + @Autowired): load period orders
    (`OrderRepository.findByCreatedAtBetween`, exclude CANCELLED/REJECTED) → `GstOrder`s; read seller
    state/gstin from `SettingsService`; run `GstEngine.compute`; build the report. Add the in/out money
    aggregates (sales/received/COD in; purchases/expenses/refunds out; receivables) reusing existing
    repository sums; net cash position. Missing-seller-state warning flag. Audit `GST_REPORT_GENERATED`.
  - Add any missing repository finders (expense sum-by-category over window; PO total over window).
  - _Requirements: 2.1, 2.2, 2.3, 3.4, 5.1, 5.3, 6.1, 6.2, 6.3, 6.4_

- [ ] 4. GST DTOs + controller (backend)
  - `GstDashboardResponse` / `GstReportResponse` (+ nested rows) with seller identity + period.
  - `gst/GstController` `/api/ca/gst` (`hasAnyRole('ADMIN','CA','ACCOUNTANT')`): `GET /dashboard`,
    `GET /report`, `GET /report/export?format=` (default period = current month; validate from ≤ to → 400).
  - _Requirements: 1.4, 5.2, 7.1, 8.1, 8.3_

- [ ] 5. GST report export (backend)
  - `GstReportExporter` building a multi-section Excel + PDF (and CSV where practical) — seller identity +
    period header, then rate-wise, HSN-wise, state-wise, and 3B summary sections — reusing POI/PDF helpers.
    Export fidelity property test (totals match computed).
  - _Requirements: 7.1, 7.2, 7.3, 8.2_

- [ ] 6. Backend verification
  - `mvn -f "backend/pom.xml" clean test` green (new GST engine/service/guard/export tests + existing
    suites, incl. `EndpointRoleGuardIntegrationTest` extended for CA).
  - _Requirements: all backend_

- [ ] 7. Frontend CA role plumbing (Angular)
  - Add `CA` to core `Role` + `STAFF_ROLES` + `staffGuard`; users page role dropdown + label/badge; add
    `adminOrCaGuard`. CA login/`DashboardComponent` redirect → `/ca/gst`. CA bottom tabs
    (GST / Orders / Reports / My Profile).
  - _Requirements: 1.1, 1.2, 1.4_

- [ ] 8. Frontend CA GST dashboard (Angular)
  - `ca-gst/` feature: `GstService` (`/api/ca/gst/{dashboard,report}` + export blob), `gst.model.ts`,
    `CaGstDashboardComponent` (route `/ca/gst`, `adminOrCaGuard`): period picker (month/quarter/custom),
    in/out KPI tiles, GST-by-rate table (CGST/SGST/IGST), HSN summary, state-wise table, GSTR-3B card with
    optional manual ITC + net payable, export buttons, empty + missing-seller-state states. Nav entry
    "GST / Accounting" (ADMIN + CA). Mobile-first + desktop full-width.
  - _Requirements: 2.3, 5.2, 5.3, 6.1, 6.2, 6.3, 6.4, 6.5, 7.1_

- [ ] 9. Frontend verification
  - `npm --prefix frontend run build:admin` clean; existing specs compile.
  - _Requirements: all frontend_

- [ ] 10. (Separable) Capture GST on inward supplies for automatic ITC
  - Migration: additive nullable `gst_rate`/`gst_amount`/`hsn` on `purchase_orders`(+items) and `expenses`;
    thread through DTOs/forms; extend `GstAccountingService` to compute period ITC (CGST/SGST/IGST) and feed
    the GSTR-3B net (replacing manual/zero ITC). Backward compatible (missing → zero ITC).
  - _Requirements: 9.1, 9.2, 9.3_

## Task Dependency Graph

```json
{
  "tasks": {
    "1": { "dependsOn": [] },
    "2": { "dependsOn": [] },
    "3": { "dependsOn": ["1", "2"] },
    "4": { "dependsOn": ["3"] },
    "5": { "dependsOn": ["3", "4"] },
    "6": { "dependsOn": ["4", "5"] },
    "7": { "dependsOn": ["2"] },
    "8": { "dependsOn": ["4", "7"] },
    "9": { "dependsOn": ["8"] },
    "10": { "dependsOn": ["6"] }
  },
  "waves": [
    { "wave": 1, "tasks": ["1", "2"] },
    { "wave": 2, "tasks": ["3", "7"] },
    { "wave": 3, "tasks": ["4"] },
    { "wave": 4, "tasks": ["5", "8"] },
    { "wave": 5, "tasks": ["6", "9"] },
    { "wave": 6, "tasks": ["10"] }
  ]
}
```

## Notes

- No schema change for Reqs 1–8; Task 10 (ITC capture) is the only migration and is optional/separable.
- Reuse existing money/receivable repository queries (`sumOutstandingCodActive`, COD-collected, etc.) and
  the existing Excel/PDF export helpers; do not duplicate.
- Follow the Clock dual-constructor + `@Autowired` primary ctor convention for the new service.
- Run `mvn clean test` (not incremental) to catch enum-switch exhaustiveness (known gotcha) when adding
  `Role.CA`.
- Keep the CA strictly read-only outside GST/finance; audit report generation (Req 8.2).
