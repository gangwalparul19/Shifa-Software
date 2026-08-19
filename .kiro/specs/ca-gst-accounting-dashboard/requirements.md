# Requirements Document

CA (Chartered Accountant) Role — Accounting & GST Reporting Dashboard

## Introduction

Shifa Herbal Remedies needs a dedicated **CA (Chartered Accountant)** role whose job is accounting,
taxation, and GST compliance. The CA needs a single place in the application to see **every money
in-flow and out-flow** the business records, and to produce a **detailed, filing-ready GST report** that
maps to what is entered on the GST portal (GSTR-1 outward supplies and the GSTR-3B summary), for any
chosen period (month / quarter / custom range).

The system already captures most of the source data:
- **Outward supplies (sales)**: each order line snapshots `hsnCode`, `gstRate`, `rate`, and `lineTotal`
  at order time; the order records the customer's `state` (place of supply). Prices are **GST-inclusive**.
- **Seller identity for GST**: `app_settings` already holds the business `gstin`, home `state`, and
  `stateCode` — enough to classify a supply as **intra-state (CGST + SGST)** vs **inter-state (IGST)**.
- **Out-flows**: purchase orders (`total_amount`), expenses (category + amount + date), and order returns
  (refund amounts) are recorded.

Gaps this feature must respect:
- Purchase orders and expenses do **not** currently capture GST/HSN, so formal **Input Tax Credit (ITC)**
  cannot be computed from them yet. The CA dashboard will show purchase/expense out-flows and a
  best-effort/optional ITC input, and capturing GST on purchases/expenses is a clearly separated later
  requirement (Req 9). The primary filing artifact is the **outward** GST report, which the data fully
  supports.
- All historical order lines already carry their tax snapshot, so GST figures are computed from immutable
  captured values and never change retroactively.

### Assumptions & Default Decisions (open for confirmation)

- **[D1] GST-inclusive.** Line prices include GST; taxable value = `lineTotal / (1 + rate/100)` and
  `tax = lineTotal − taxable` (consistent with the existing order pricing engine).
- **[D2] Place of supply = the order's `state`.** Intra-state when it equals the seller's `app_settings`
  state (CGST + SGST, each = tax/2); otherwise inter-state (IGST = full tax).
- **[D3] B2C.** Customers are individuals; the outward report is summarised (rate-wise, HSN-wise,
  state-wise, B2C) rather than invoice-level B2B. Cancelled / rejected orders are excluded from tax.
- **[D4] CA is a finance role.** The CA has read access to the same financial data as ACCOUNTANT (orders,
  reports, finance/P&L, expenses, procurement, returns, reconciliation) plus the new GST dashboard/report;
  the CA does not approve orders or perform fulfilment/mutations outside finance/GST.
- **[D5] Reporting period.** The default period is the current month; the CA can pick month, quarter
  (Indian FY quarters), or a custom from/to range. Amounts are computed over that window.

## Glossary

- **CA**: Chartered Accountant — the new finance/tax role this feature introduces.
- **Outward supply**: a sale (order) — the business's taxable output.
- **Inward supply**: a purchase / expense — a potential input tax credit.
- **Taxable value**: the pre-tax amount of a line/order (GST-inclusive price minus its GST component).
- **Output tax**: GST collected on outward supplies (CGST + SGST + IGST).
- **CGST / SGST**: central & state GST on an intra-state supply (each half the line's GST).
- **IGST**: integrated GST on an inter-state supply (the full line GST).
- **Place of supply**: the destination state that determines intra- vs inter-state classification.
- **HSN summary**: per-HSN totals (quantity, taxable value, tax) required by GSTR-1.
- **Rate-wise summary**: totals grouped by GST rate (0 / 5 / 18 %).
- **State-wise summary**: totals grouped by place-of-supply state.
- **GSTR-1**: the outward-supplies return filed on the GST portal.
- **GSTR-3B**: the summary return (output tax, ITC, net payable).
- **ITC (Input Tax Credit)**: GST paid on inward supplies, offsettable against output tax.
- **Reporting period**: the month / quarter / custom date range a report is computed over.

## Requirements

### Requirement 1: CA role and access

**User Story:** As an admin, I want a dedicated CA role, so that our accountant can access accounting and
GST features without fulfilment or approval permissions.

#### Acceptance Criteria

1. THE system SHALL provide a distinct **CA** role, selectable when an admin creates or edits a user.
2. WHEN a CA signs in, THE system SHALL grant read access to orders, reports, finance/P&L, expenses,
   procurement, returns, and reconciliation, plus the new GST dashboard and GST report.
3. THE system SHALL NOT allow a CA to approve orders, run fulfilment (packing/dispatch), or mutate
   catalog/customer/order data beyond finance/GST actions.
4. WHEN a CA signs in, THE system SHALL land them on the CA (GST) dashboard.
5. WHERE an endpoint is CA-relevant and already permits ACCOUNTANT, THE system SHALL also permit CA.

### Requirement 2: Seller GST configuration is the basis for tax classification

**User Story:** As a CA, I want the business's GSTIN and home state used consistently, so that supplies are
correctly split into CGST/SGST vs IGST.

#### Acceptance Criteria

1. THE system SHALL read the seller's GSTIN, state, and state code from application settings.
2. WHERE the seller state is configured, THE system SHALL classify an order whose place-of-supply state
   equals the seller state as **intra-state** and all others as **inter-state**.
3. IF the seller state is not configured, THEN THE system SHALL surface a clear warning on the GST
   dashboard that state-wise CGST/SGST vs IGST classification is unavailable until it is set.

### Requirement 3: Outward GST computation (per order / line)

**User Story:** As a CA, I want each sale's GST computed from its captured line tax data, so that the report
is accurate and immutable.

#### Acceptance Criteria

1. THE system SHALL compute each order line's taxable value and GST from its snapshotted `lineTotal` and
   `gstRate`, treating prices as GST-inclusive ([D1]).
2. WHERE a supply is intra-state, THE system SHALL split the line GST into equal CGST and SGST halves;
   WHERE inter-state, THE system SHALL record the full GST as IGST.
3. THE system SHALL exclude cancelled and rejected orders from all GST/tax figures.
4. THE system SHALL attribute an order to a reporting period by its order (creation) date.
5. THE system SHALL sum line-level taxable/tax to order level and never recompute historical values from
   the current catalog (uses the snapshot).

### Requirement 4: Rate-wise, HSN-wise, and state-wise summaries

**User Story:** As a CA, I want the outward supplies summarised the way the GST portal expects, so that I
can transcribe them into GSTR-1.

#### Acceptance Criteria

1. THE system SHALL produce a **rate-wise** summary for the period: per GST rate — taxable value, CGST,
   SGST, IGST, total tax, and total invoice value.
2. THE system SHALL produce an **HSN-wise** summary for the period: per HSN code — description (product
   name(s)), total quantity, taxable value, and CGST/SGST/IGST amounts.
3. THE system SHALL produce a **state-wise** summary for the period: per place-of-supply state — taxable
   value and IGST (inter-state) or CGST+SGST (intra-state), with the state code where known.
4. WHERE there are no qualifying orders in the period, THE system SHALL return empty summaries (not an
   error) and the dashboard SHALL show a clear empty state.
5. THE system SHALL ensure the sum of the rate-wise, HSN-wise, and state-wise taxable/tax totals each
   reconcile to the same period output-tax total (internal consistency).

### Requirement 5: GSTR-3B-style summary (net GST position)

**User Story:** As a CA, I want a summary of output tax, ITC, and net payable, so that I can complete
GSTR-3B.

#### Acceptance Criteria

1. THE system SHALL present, for the period, total taxable outward value and total output tax broken into
   CGST, SGST, and IGST.
2. THE system SHALL present an ITC section; WHERE inward GST is not captured (Req 9 not yet in effect),
   THE system SHALL show ITC as zero / not-available with an explanatory note and MAY accept a manual ITC
   figure entered by the CA for the net calculation.
3. THE system SHALL compute the net GST payable as output tax minus available ITC (floored at zero for
   display), clearly labelling any manual ITC input.

### Requirement 6: Money in/out dashboard

**User Story:** As a CA, I want to see every recorded money in-flow and out-flow for the period, so that I
have a complete accounting picture alongside GST.

#### Acceptance Criteria

1. THE system SHALL show period **in-flows**: gross sales (invoice value), taxable sales, output GST,
   amount received, and COD collected.
2. THE system SHALL show period **out-flows**: purchases (purchase-order totals), expenses (total and by
   category), and refunds from returns.
3. THE system SHALL show **receivables**: outstanding customer dues and COD pending from the courier as of
   the period end.
4. THE system SHALL present a net cash position (in-flows minus out-flows) for the period, clearly labelled
   as an operational view (not a statutory statement).
5. THE dashboard SHALL let the CA change the period (month / quarter / custom) and refresh all figures.

### Requirement 7: Filing-ready GST report export

**User Story:** As a CA, I want to export the GST report, so that I can use it to fill the GST portal and
share it with stakeholders.

#### Acceptance Criteria

1. THE system SHALL let the CA export the outward GST report (rate-wise, HSN-wise, state-wise, and the
   3B-style summary) for the selected period.
2. THE system SHALL support Excel and PDF (and CSV where practical) export, reusing the existing report
   export pipeline.
3. THE exported report SHALL include the seller identity (legal name, GSTIN, state) and the period, and
   the figures SHALL match exactly what the dashboard shows for that period.

### Requirement 8: Access control and auditability

**User Story:** As an admin, I want the CA's access scoped and their report generation auditable, so that
financial data stays controlled.

#### Acceptance Criteria

1. THE system SHALL restrict the GST dashboard and report endpoints to ADMIN and CA (and ACCOUNTANT where
   appropriate), rejecting other roles with 403.
2. THE system SHALL record an audit event when a GST report is generated/exported (who, which period).
3. THE system SHALL NOT expose customer PII beyond what existing finance/reports already expose.

### Requirement 9: (Separable) Capture GST on inward supplies for ITC

**User Story:** As a CA, I want purchases and expenses to record their GST, so that Input Tax Credit can be
computed automatically.

#### Acceptance Criteria

1. THE system SHALL allow a purchase order and an expense to record a GST rate and/or GST amount and an
   HSN/SAC where applicable.
2. WHERE inward GST is captured, THE system SHALL compute period ITC (CGST/SGST/IGST) and feed it into the
   GSTR-3B-style net calculation (Req 5), replacing the manual/zero ITC.
3. THE system SHALL keep this additive and backward compatible (existing purchases/expenses without GST
   read as zero ITC).

> Note: Requirement 9 is intentionally separable — the CA dashboard and the outward filing report (Reqs
> 1–8) are fully deliverable without it. Prioritise 1–8; schedule 9 when automatic ITC is needed.
