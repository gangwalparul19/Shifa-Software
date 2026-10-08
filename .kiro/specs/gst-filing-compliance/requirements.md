# Requirements Document

GST Filing Compliance — Portal-Ready GSTR-1 / GSTR-3B for the CA

## Introduction

This feature **extends** the already-shipped CA GST accounting dashboard
(`.kiro/specs/ca-gst-accounting-dashboard/`) so that GST tracking and reporting become truly
**filing-ready**: the CA should be able to **import** the business's figures into the Indian GST
portal (or the GST Offline Tool) instead of re-typing them.

The existing system already provides, and this feature builds on (does **not** re-implement):
- A read-only **CA** role and the `/api/ca/gst` dashboard / report / export endpoints.
- A pure `gst/domain/GstEngine` that extracts GST from **GST-inclusive** order line snapshots
  (`hsnCode`, `gstRate`, `lineTotal`) and produces rate-wise, HSN-wise, and state-wise summaries plus
  a GSTR-3B-style total, classifying each supply as intra-state (CGST/SGST) vs inter-state (IGST)
  against the seller state held in `app_settings`.
- A money in/out dashboard, CSV + PDF export, drill-down to contributing orders, and period presets
  (This Month / Last Month / This Quarter / This FY / Last FY).
- Seller GST identity (`gstin`, `state`, `stateCode`) in `app_settings`.

The business is a D2C herbal seller on the **regular** GST scheme, prices are **GST-inclusive**, and
supplies are **mostly B2C**.

The work is organised into three tiers. **Tier 1 is the primary, independently shippable deliverable**
that makes GSTR-1 filing "direct" (portal-importable). Tier 2 improves GSTR-3B accuracy and Input Tax
Credit. Tier 3 hardens compliance (period locking, amendments, filing calendar, audit snapshots).

### Assumptions & Default Decisions

- **[A1] GST-inclusive pricing.** Taxable value is extracted from the line total exactly as the existing
  `GstEngine` does; this feature does not change the extraction math.
- **[A2] Place of supply = the order's delivery `state`.** State code comes from the new state-code
  master (Requirement 6).
- **[A3] Historical order tax snapshots are immutable.** New classification is derived from captured
  data and never rewrites historical line snapshots.
- **[A4] Additive and backward compatible.** Existing orders with no buyer GSTIN are treated as B2C;
  every new field is nullable/optional; existing endpoints and the `GstEngine` are reused, not replaced.
- **[A5] Indian financial-year periods** (April–March) and Indian FY quarters, consistent with the
  existing dashboard presets.
- **[A6] Customer PII exposure is no broader** than what existing finance/reports already expose.
- **[A7] Regular scheme, B2C-dominant.** B2B/B2CL are the exception; the default supply is B2CS
  (small B2C, summarised).

## Glossary

- **Filing_System**: the GST-filing-compliance feature described by this document (the CA-facing
  filing-ready reporting and export capability built on the existing GST dashboard).
- **GstEngine**: the existing pure GST computation engine that this feature extends.
- **GSTR1_Exporter**: the component that renders period figures into GST Offline Tool section-wise
  format (per-section CSV and/or the portal JSON schema).
- **CDN_Engine**: the component that turns order returns/refunds into credit/debit notes.
- **ITC_Engine**: the component that computes Input Tax Credit from inward-supply GST.
- **State_Code_Master**: the reference table of 2-digit GST state codes keyed to state names.
- **Buyer GSTIN**: the customer's GST Identification Number, captured on an order when the buyer is a
  registered person.
- **B2B**: supply to a GST-registered buyer (has a valid GSTIN) — reported invoice-level in GSTR-1.
- **B2CS (B2C Small)**: supply to an unregistered buyer that is not a B2CL invoice — reported
  summarised (rate + place-of-supply) in GSTR-1.
- **B2CL (B2C Large)**: an **inter-state** supply to an unregistered buyer with **invoice value greater
  than ₹2,50,000** — reported invoice-level in GSTR-1.
- **CDNR**: Credit/Debit Notes (Registered) — notes issued against B2B supplies.
- **CDNUR**: Credit/Debit Notes (Unregistered) — notes issued against B2C supplies.
- **B2BA / B2CSA / CDNRA**: the amendment tables in GSTR-1 for correcting previously filed B2B, B2CS,
  and CDNR entries.
- **UQC (Unit Quantity Code)**: the GST-standard unit-of-measure code (e.g. NOS, PCS, KGS, MLT) required
  in the HSN summary.
- **HSN**: Harmonized System of Nomenclature code classifying goods/services for GST.
- **GSTR-1**: the outward-supplies return filed on the GST portal (sections b2b, b2cl, b2cs, cdnr,
  cdnur, hsn, docs, and amendment tables).
- **GSTR-3B**: the summary self-assessment return (outward tax, ITC, net payable).
- **GSTR-2B**: the auto-drafted ITC statement on the portal (referenced for ITC reconciliation context).
- **ITC (Input Tax Credit)**: GST paid on inward supplies (purchases/expenses), offsettable against
  output tax.
- **Place of supply**: the destination state that determines intra- vs inter-state classification and
  carries a 2-digit state code.
- **RCM (Reverse Charge Mechanism)**: supplies on which the recipient, not the supplier, pays GST.
- **Nil-rated / Exempt / Non-GST**: supply classifications carrying no output tax, reported separately.
- **Documents Issued (Table 13)**: the GSTR-1 summary of invoice/document number series issued and
  cancelled.
- **HSN summary (Table 12)**: the GSTR-1 per-HSN summary (HSN, UQC, quantity, rate, taxable value, tax).
- **Reporting period**: the month (or Indian FY quarter / custom range) a report is computed over.
- **Filed period**: a reporting period the CA has marked as filed, after which its figures are locked.
- **Turnover threshold**: the ₹5 crore aggregate-turnover level above which e-invoicing (IRN/QR)
  becomes mandatory.

## Requirements

## Tier 1 — Portal-ready GSTR-1 outward supplies (primary, independently shippable)

### Requirement 1: Capture buyer GSTIN and classify outward supplies (B2B / B2CS / B2CL / B2C)

**User Story:** As a salesperson creating an order, I want to optionally record the buyer's GSTIN, so
that registered-buyer and large-value sales are classified correctly for GSTR-1.

#### Acceptance Criteria

1. WHERE an order is created or edited, THE Filing_System SHALL accept an optional buyer GSTIN of up to
   15 characters.
2. WHEN a buyer GSTIN is provided, THE Filing_System SHALL validate that the GSTIN matches the standard
   15-character GSTIN format (2-digit state code, 10-character PAN, 1 entity digit, the fixed letter Z,
   1 checksum character).
3. IF a provided buyer GSTIN fails format validation, THEN THE Filing_System SHALL reject the order
   submission with a message identifying the GSTIN field and the expected format.
4. WHEN an order has a valid buyer GSTIN, THE Filing_System SHALL classify the order as **B2B**.
5. WHEN an order has no buyer GSTIN AND the supply is inter-state AND the order invoice value is greater
   than ₹2,50,000, THE Filing_System SHALL classify the order as **B2CL**.
6. WHEN an order has no buyer GSTIN AND is neither B2B nor B2CL, THE Filing_System SHALL classify the
   order as **B2CS**.
7. THE Filing_System SHALL classify every existing order that has no buyer GSTIN using rules 5 and 6,
   so that no historical order requires a GSTIN to be reportable.
8. THE Filing_System SHALL determine each order's supply classification from its captured data without
   modifying the order's stored line tax snapshots.

### Requirement 2: Credit/Debit notes from returns and refunds (CDNR / CDNUR)

**User Story:** As a CA, I want order returns and refunds represented as credit/debit notes, so that
output tax is reduced in the correct period and reported in GSTR-1.

#### Acceptance Criteria

1. WHEN an order return with a refund is recorded, THE CDN_Engine SHALL derive a credit note carrying the
   note value, taxable value, and GST split (CGST/SGST or IGST) computed on the returned amount using the
   same GST-inclusive extraction as outward supplies.
2. WHERE the original order is B2B, THE CDN_Engine SHALL report the derived note under **CDNR**; WHERE the
   original order is B2C, THE CDN_Engine SHALL report the derived note under **CDNUR**.
3. THE CDN_Engine SHALL attribute each credit/debit note to the reporting period containing the note's
   (return/refund) date.
4. THE Filing_System SHALL reduce the period's output tax by the total GST of the credit notes attributed
   to that period.
5. THE CDN_Engine SHALL reference each note to its original order (document reference), and SHALL carry
   the place-of-supply state and 2-digit state code of the original order.
6. THE Filing_System SHALL exclude returns/refunds against cancelled or rejected orders from credit-note
   figures.

### Requirement 3: HSN summary per GSTR-1 Table 12 (with UQC)

**User Story:** As a CA, I want a Table-12-compliant HSN summary, so that I can import the HSN section
into the portal.

#### Acceptance Criteria

1. THE Filing_System SHALL produce, for the reporting period, a per-HSN summary containing HSN code, UQC
   (unit of measure), GST rate, total quantity, total taxable value, and total tax (CGST, SGST, IGST).
2. THE Filing_System SHALL associate each product with a UQC drawn from the GST-standard UQC set, using a
   default UQC where a product has none assigned.
3. WHERE the seller's aggregate turnover requires 6-digit HSN reporting, THE Filing_System SHALL enforce
   a minimum HSN length of 6 digits; otherwise THE Filing_System SHALL enforce a minimum HSN length of
   4 digits.
4. IF a product's HSN code is shorter than the enforced minimum length, THEN THE Filing_System SHALL flag
   that HSN row as non-compliant with a message identifying the product and required length.
5. THE Filing_System SHALL group HSN rows by the combination of HSN code and GST rate.

### Requirement 4: Documents Issued summary per GSTR-1 Table 13

**User Story:** As a CA, I want a documents-issued summary, so that GSTR-1 Table 13 reflects our invoice
number series.

#### Acceptance Criteria

1. THE Filing_System SHALL produce, for the reporting period, a documents-issued summary containing, per
   invoice-number series, the from-number, the to-number, the total count issued, and the count
   cancelled.
2. THE Filing_System SHALL count an order's invoice as **cancelled** when the order is in a cancelled or
   rejected state, and as **issued** otherwise.
3. THE Filing_System SHALL derive the number series from the existing invoice-numbering scheme
   (invoice sequence) used by the invoice module.
4. WHERE no invoices were issued in the period, THE Filing_System SHALL return an empty documents-issued
   summary rather than an error.

### Requirement 5: Section-wise GSTR-1 export in GST Offline Tool format

**User Story:** As a CA, I want to export GSTR-1 in the portal's section-wise format, so that I can import
it directly into the GST Offline Tool or portal.

#### Acceptance Criteria

1. THE GSTR1_Exporter SHALL export the reporting period's outward data organised into the portal sections
   **b2b, b2cl, b2cs, cdnr, cdnur, hsn, and docs**.
2. THE GSTR1_Exporter SHALL produce per-section CSV files matching the GST Offline Tool column layout for
   each section.
3. THE GSTR1_Exporter SHALL produce a portal-schema JSON document containing the same sections, structured
   for import into the GST portal.
4. THE GSTR1_Exporter SHALL include the seller GSTIN and the return period (month and financial year) in
   the exported artifacts.
5. THE totals in every exported section SHALL reconcile to the same period figures shown on the CA GST
   dashboard.
6. WHERE a section has no data for the period, THE GSTR1_Exporter SHALL omit that section or emit it empty
   in a manner accepted by the GST Offline Tool, rather than failing the export.

### Requirement 6: GST state-code master

**User Story:** As a CA, I want every place-of-supply row to carry the correct 2-digit GST state code, so
that inter-state rows and B2CL/B2CS place-of-supply values import correctly.

#### Acceptance Criteria

1. THE State_Code_Master SHALL provide the mapping between each Indian state/union-territory name and its
   2-digit GST state code.
2. WHEN the Filing_System produces any place-of-supply row, THE Filing_System SHALL attach the 2-digit
   state code resolved from the State_Code_Master.
3. IF an order's place-of-supply state name does not resolve to a state code, THEN THE Filing_System SHALL
   flag that row as needing a state-code mapping and identify the unresolved state name.
4. THE State_Code_Master SHALL resolve the seller's home state code for CGST/SGST vs IGST classification
   consistently with the existing intra/inter classification.

## Tier 2 — GSTR-3B accuracy and Input Tax Credit

### Requirement 7: Capture GST on purchases and expenses for automatic ITC

**User Story:** As a CA, I want purchases and expenses to record their GST, so that Input Tax Credit is
computed automatically and feeds GSTR-3B.

#### Acceptance Criteria

1. WHERE a purchase order or an expense is created or edited, THE Filing_System SHALL accept an optional
   GST rate, GST amount, and HSN/SAC code.
2. WHEN inward GST is captured for the reporting period, THE ITC_Engine SHALL compute period ITC split
   into CGST, SGST, and IGST.
3. THE ITC_Engine SHALL feed the computed ITC into the GSTR-3B Table 4 figures and the net-payable
   calculation, replacing the current manual/zero ITC input.
4. WHERE a purchase or expense has no captured GST, THE ITC_Engine SHALL treat its ITC contribution as
   zero, so that existing records without GST remain valid.
5. THE Filing_System SHALL compute net GST payable as period output tax minus available ITC, not below
   zero for display.

### Requirement 8: Nil-rated, exempt, non-GST, and reverse-charge classification

**User Story:** As a CA, I want supplies classified by their GST treatment, so that nil-rated, exempt,
non-GST, and reverse-charge amounts are reported in the correct GSTR-3B rows.

#### Acceptance Criteria

1. THE Filing_System SHALL classify each outward line as taxable, nil-rated, exempt, or non-GST based on
   its GST rate and product classification.
2. THE Filing_System SHALL report nil-rated, exempt, and non-GST outward values separately from taxable
   outward values.
3. WHERE a supply is marked reverse-charge, THE Filing_System SHALL report its value under the
   reverse-charge figures rather than as normal outward tax.
4. THE Filing_System SHALL treat a supply as taxable by default when no other classification applies.

### Requirement 9: Explicit mapping to GSTR-3B sections

**User Story:** As a CA, I want dashboard figures mapped to named GSTR-3B sections, so that I can transcribe
them directly.

#### Acceptance Criteria

1. THE Filing_System SHALL present the period's taxable outward supplies and output tax mapped to GSTR-3B
   section **3.1(a)** (outward taxable supplies other than zero-rated, nil, and exempt).
2. THE Filing_System SHALL present inter-state supplies made to unregistered persons mapped to GSTR-3B
   section **3.2**, broken down by place-of-supply state.
3. THE Filing_System SHALL present eligible ITC mapped to GSTR-3B **Table 4**, split into CGST, SGST, and
   IGST.
4. THE figures mapped to each GSTR-3B section SHALL reconcile to the corresponding rate-wise, state-wise,
   and ITC totals for the same period.

## Tier 3 — Compliance hardening (lower priority / optional)

### Requirement 10: Filed-period lock and amendment tables

**User Story:** As a CA, I want a filed period to become immutable with corrections routed to amendments,
so that filed figures cannot silently change.

#### Acceptance Criteria

1. WHEN the CA marks a reporting period as filed, THE Filing_System SHALL record the period as a filed
   period.
2. WHILE a period is filed, THE Filing_System SHALL present that period's outward figures as read-only and
   SHALL indicate that the period is locked.
3. WHEN a transaction that affects a filed period changes after the period is filed, THE Filing_System
   SHALL route the correction to the corresponding amendment table (**B2BA**, **B2CSA**, or **CDNRA**) in
   a later open period.
4. THE Filing_System SHALL restrict marking a period filed and creating amendments to the ADMIN and CA
   roles.

### Requirement 11: Filing calendar, reminders, and audit snapshot

**User Story:** As a CA, I want filing due-date reminders and a snapshot of what was filed, so that I file
on time and can audit past filings.

#### Acceptance Criteria

1. THE Filing_System SHALL present a filing calendar with the GSTR-1 due date (the 11th of the following
   month) and the GSTR-3B due date (the 20th of the following month) for each reporting period.
2. WHILE a period's GSTR-1 or GSTR-3B is unfiled and its due date is within the reminder window, THE
   Filing_System SHALL surface a reminder identifying the return and its due date.
3. WHEN the CA marks a period as filed, THE Filing_System SHALL store an immutable audit snapshot of the
   exact figures filed for that period, together with who filed it and when.
4. WHEN the CA views a past filed period, THE Filing_System SHALL display the stored audit snapshot rather
   than recomputing the figures.

### Requirement 12: E-invoicing scope boundary

**User Story:** As the business owner, I want e-invoicing treated as out of scope until we cross the
turnover threshold, so that we do not build unneeded IRN/QR complexity prematurely.

#### Acceptance Criteria

1. THE Filing_System SHALL treat e-invoicing (IRN generation and QR codes) as out of scope while the
   seller's aggregate turnover is at or below the ₹5 crore turnover threshold.
2. WHERE the seller's configured aggregate turnover exceeds the turnover threshold, THE Filing_System SHALL
   surface a notice that e-invoicing is required and currently out of scope.

## Cross-cutting requirements

### Requirement 13: Access control and PII scope

**User Story:** As an admin, I want the new filing features scoped to finance roles and PII kept minimal,
so that sensitive data stays controlled.

#### Acceptance Criteria

1. THE Filing_System SHALL restrict the GSTR-1 export, ITC, period-lock, and filing-calendar endpoints to
   the ADMIN and CA roles (and ACCOUNTANT where those pages are already shared), rejecting other roles
   with a 403 response.
2. THE Filing_System SHALL NOT expose customer PII beyond what the existing finance and reports features
   already expose.
3. WHEN a GSTR-1 export or period-filing action occurs, THE Filing_System SHALL record an audit event
   capturing the actor, the action, and the reporting period.

### Requirement 14: Additive, backward-compatible integration

**User Story:** As a developer, I want the feature to reuse the existing GST engine and pipelines without
breaking historical data, so that the change is safe to ship incrementally.

#### Acceptance Criteria

1. THE Filing_System SHALL reuse the existing GstEngine extraction and intra/inter classification for all
   outward and credit-note tax computation.
2. THE Filing_System SHALL add only nullable/optional fields to existing orders, purchases, and expenses,
   so that existing rows remain valid without backfill.
3. THE Filing_System SHALL leave historical order line tax snapshots unchanged.
4. THE Filing_System SHALL allow Tier 1 (Requirements 1–6) to be delivered and used independently of
   Tiers 2 and 3.
