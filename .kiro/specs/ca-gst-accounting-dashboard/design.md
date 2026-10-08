# Design Document

CA (Chartered Accountant) Role — Accounting & GST Reporting Dashboard

## Overview

Introduce a **CA** role and a GST/accounting dashboard + filing-ready GST report, built on existing data:
order line tax snapshots (`hsnCode`, `gstRate`, `rate`, `lineTotal`), the order's `state` (place of
supply), and the seller's `gstin`/`state`/`stateCode` in `app_settings`. A pure GST engine turns orders
in a period into rate-wise / HSN-wise / state-wise summaries and a GSTR-3B-style net position, plus an
in/out money view. Exports reuse the existing Excel/PDF report pipeline.

The work is mostly **additive and read-only** on the sales side (no schema change needed for Reqs 1–8).
Only the CA role enum value and endpoint authorisations change existing code. Req 9 (inward GST capture)
is a later, separable migration.

## Architecture

```
CA login ──► CA (GST) dashboard route (adminOrCaGuard)
                 │
   GET /api/ca/gst/dashboard?from&to    ── GstAccountingService.dashboard(period)
   GET /api/ca/gst/report?from&to       ── GstAccountingService.report(period)
   GET /api/ca/gst/report/export?...     ── existing export pipeline (Excel/PDF/CSV)
                 │
        GstAccountingService (@Service, read-only tx)
          • loads orders in [from,to) via OrderRepository.findByCreatedAtBetween (excl. CANCELLED/REJECTED)
          • loads seller state/gstin from SettingsService
          • loads out-flows: PurchaseOrderRepository / ExpenseRepository / OrderReturnRepository totals
          • loads receivables: existing receivable/COD queries
                 │
        GstEngine (pure domain, no Spring)
          • per line: taxable = lineTotal/(1+rate/100); tax = lineTotal − taxable
          • intra vs inter (order.state vs sellerState) → CGST/SGST or IGST
          • aggregate → RateWiseRow[], HsnRow[], StateWiseRow[], Gstr3bSummary
                 │
        DTOs → GstDashboardResponse / GstReportResponse → Angular CA feature
```

## Components and Interfaces

### 1. Role (Req 1)

- Backend `auth/Role`: add `CA`. Add to `STAFF_ROLES` (users create/edit). Frontend `core` `Role`: add
  `CA`; add to `STAFF_ROLES`, `staffGuard`, users role label/badge.
- New guard `adminOrCaGuard` (ADMIN + CA) for the CA routes; `financeReadGuard`/existing accountant guards
  extended to include CA where those pages are shared (reports, finance, expenses, procurement, returns,
  reconciliation).
- Backend endpoint `@PreAuthorize` on shared finance/report endpoints: add `'CA'` alongside `'ACCOUNTANT'`.
- `DashboardComponent`/login redirect: a CA lands on `/ca/gst` (new route).

### 2. Pure GST engine (Reqs 3, 4, 5)

New package `com.shifa.oms.gst.domain` (pure, unit-testable):

```
enum SupplyType { INTRA, INTER }
record GstLine(String hsn, String productName, BigDecimal gstRate, int quantity,
               BigDecimal lineTotal)                       // GST-inclusive
record GstOrder(Long orderId, String state, LocalDate date, List<GstLine> lines)
record TaxSplit(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) // + total()
record RateWiseRow(BigDecimal rate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
                   BigDecimal igst, BigDecimal invoiceValue)
record HsnRow(String hsn, String description, BigDecimal quantity, BigDecimal taxable,
              BigDecimal cgst, BigDecimal sgst, BigDecimal igst)
record StateWiseRow(String state, String stateCode, SupplyType type, BigDecimal taxable,
                    BigDecimal cgst, BigDecimal sgst, BigDecimal igst)
record Gstr3bSummary(BigDecimal taxableOutward, BigDecimal outputCgst, BigDecimal outputSgst,
                     BigDecimal outputIgst, BigDecimal outputTotal)
record GstComputation(RateWiseRow[] rateWise, HsnRow[] hsn, StateWiseRow[] stateWise,
                      Gstr3bSummary summary)

class GstEngine {
  static TaxSplit splitLine(GstLine line, SupplyType type)   // extract inclusive GST, split
  static GstComputation compute(List<GstOrder> orders, String sellerState)
}
```

- Extraction mirrors `OrderPricing.extractGst`: `taxable = lineTotal / (1 + rate/100)` (HALF_UP, 2dp),
  `tax = lineTotal − taxable`; zero/null rate → zero tax.
- `SupplyType`: INTRA when `equalsIgnoreCase(order.state, sellerState)`, else INTER. When `sellerState`
  is blank, default to INTER and the service flags the warning (Req 2.3).
- Reconciliation (Correctness Properties): the summed taxable/tax of rateWise == hsn == stateWise ==
  summary totals.

### 3. Application service (Reqs 3–7)

`com.shifa.oms.gst.GstAccountingService` (@Service, `@Transactional(readOnly = true)`, `Clock` for the
default period, `@Autowired` primary ctor per the dual-ctor convention):

- `GstReport report(LocalDate from, LocalDate to)`:
  - `orders = orderRepository.findByCreatedAtBetween(fromStart, toEnd)` filtered to non-CANCELLED/REJECTED;
    map each to `GstOrder` (state + line snapshots).
  - `seller = settingsService.getSettings()` → sellerState/stateCode/gstin/legalName.
  - `GstEngine.compute(...)` → rate/HSN/state summaries + 3B summary.
  - Wrap into `GstReportResponse` incl. seller identity + period.
- `GstDashboard dashboard(from, to)`:
  - report() output plus in/out money: sales invoice value, amountReceived, COD collected
    (`OrderRepository` sums already used by finance/reports), purchases total
    (`PurchaseOrderRepository` sum over window), expenses total + by category (`ExpenseRepository`),
    refunds (`OrderReturnRepository`), receivables (existing outstanding + COD-pending queries).
  - net cash position = inflows − outflows.
- `report`/`export` record an audit event `GST_REPORT_GENERATED` (Req 8.2).
- Add repository finders as needed: `OrderRepository.findByCreatedAtBetween` (exists — used by daily
  report); `ExpenseRepository` sum-by-category over window; `PurchaseOrderRepository` sum over window.
  Reuse existing money/receivable queries (`sumOutstandingCodActive`, etc.).

### 4. Controller (Reqs 1, 7, 8)

`gst/GstController` `/api/ca/gst` (class `@PreAuthorize hasAnyRole('ADMIN','CA','ACCOUNTANT')`):
- `GET /dashboard?from&to` → `GstDashboardResponse`
- `GET /report?from&to` → `GstReportResponse`
- `GET /report/export?from&to&format=xlsx|pdf|csv` → binary (reuse `ReportExport*`/POI/PDF exporters;
  a small `GstReportExporter` builds the multi-section workbook/pdf). No SecurityConfig change (`/api/**`
  authenticated + method security). Default period = current month when params omitted.

### 5. Frontend (Reqs 1, 6, 7)

- `core` Role + STAFF_ROLES + staffGuard include `CA`; users page role dropdown + label/badge.
- New Angular feature `ca-gst/`:
  - `GstService` (`/api/ca/gst/{dashboard,report}` + export blob).
  - `gst.model.ts` (dashboard + report DTOs).
  - `CaGstDashboardComponent` (route `/ca/gst`, `adminOrCaGuard`): period picker (month/quarter/custom),
    in/out KPI tiles, GST-by-rate table (CGST/SGST/IGST), HSN summary table, state-wise table, GSTR-3B
    summary card with optional manual ITC + net payable, and export buttons. Mobile-first, full-width on
    desktop (uses the new desktop layout).
  - Nav: a top-level "GST / Accounting" entry (roles ADMIN + CA) under Analytics & Reports; CA bottom-tab
    set = Dashboard(GST) / Orders / Reports / My Profile.
- `DashboardComponent` / auth redirect: CA → `/ca/gst`.

## Data Models

No schema change for Reqs 1–8 (CA is an enum value; all figures derive from existing columns + settings).
Req 9 (later) adds nullable `gst_rate`/`gst_amount`/`hsn` to `purchase_orders`(+items) and `expenses`.

## Correctness Properties

### Property 1: Inclusive extraction bound
For any line, `0 ≤ tax ≤ lineTotal` and `taxable = lineTotal − tax`; a zero/blank rate yields zero tax.

**Validates: Requirements 3.1**

### Property 2: Split integrity
Intra-state: `cgst == sgst` and `cgst + sgst == lineTax`; inter-state: `igst == lineTax` and
`cgst == sgst == 0`.

**Validates: Requirements 3.2**

### Property 3: Cross-summary reconciliation
Total taxable and total tax are identical across the rate-wise, HSN-wise, and state-wise summaries and the
GSTR-3B summary for the same period.

**Validates: Requirements 4.5**

### Property 4: Exclusion
Cancelled/rejected orders contribute nothing to any GST figure.

**Validates: Requirements 3.3**

### Property 5: Period attribution
An order is counted in exactly the period containing its order date.

**Validates: Requirements 3.4**

### Property 6: Export fidelity
Exported totals equal the dashboard/report totals for the same period.

**Validates: Requirements 7.3**

## Error Handling

- Missing seller state → dashboard warning (Req 2.3); classification defaults to inter-state, clearly noted.
- Empty period → empty summaries + empty-state UI, never a 500.
- Non-CA/ADMIN/ACCOUNTANT access → 403 (method security), rendered as JSON via the global handler.
- Invalid date range (from > to) → 400 with a clear message.

## Testing Strategy

- **Pure `GstEngine`**: unit + jqwik property tests for the Correctness Properties (extraction bound,
  split integrity, cross-summary reconciliation, exclusion, rounding).
- **`GstAccountingService`**: period windowing, seller-state classification (intra vs inter), in/out
  aggregation, empty period; using recording/real collaborators (Java 25 — no Mockito on concretes).
- **Endpoint guard**: CA allowed, other non-finance roles 403 (extend `EndpointRoleGuardIntegrationTest`).
- **Export fidelity**: exported totals match computed totals (mirror `ReportExportFidelityPropertyTest`).
- Full backend `mvn clean test` green; admin `build:admin` clean.
