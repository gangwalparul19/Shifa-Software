# Shifa OMS → ERP Roadmap

> Plan to evolve the Shifa Order Management System into a full ERP so the client can
> operate from a single application and retire **Tally** (and other point tools) for
> accounting, GST, payroll/HR, and inventory/finance.

## Goal

Make Shifa OMS the **single source of truth** for the business:
- All **accounting** (double-entry, financial statements) lives here — retire Tally.
- All **GST** compliance (returns, ITC, e-invoicing) is filed from here.
- **Payroll & HR** (employees, attendance, leave, salary, statutory) runs here.
- **Inventory, procurement, sales, receivables** (already here) post into the same ledger.

## Guiding architectural principle

**The General Ledger (GL) is the backbone.** Every module posts double-entry journal
entries into the GL:

| Business event            | Debit                     | Credit                          |
|---------------------------|---------------------------|---------------------------------|
| Sales invoice             | Debtor / Cash-Bank        | Sales + GST Output              |
| Purchase bill             | Purchases + GST Input     | Creditor                        |
| Customer payment received | Cash / Bank               | Debtor                          |
| Vendor payment made       | Creditor                  | Cash / Bank                     |
| Expense                   | Expense account           | Cash / Bank / Creditor          |
| Payroll run               | Salary + Employer PF/ESI  | Salary payable + PF/ESI/TDS/PT  |
| Depreciation              | Depreciation expense      | Accumulated depreciation        |

Once every subsystem posts to the GL, the Balance Sheet, P&L, Cash Flow, GST returns,
and TDS returns all derive from **one authoritative ledger** — which is exactly what
makes Tally redundant.

## Current state (what already exists in Shifa OMS)

Roughly **40–50% of an ERP** is already in place:

- **Order-to-cash**: order lifecycle, packing, courier (mock), returns, GST invoicing,
  COD receivables, payment verification.
- **Inventory**: products, stock movements, price bands, HSN/GST/UQC.
- **Procurement**: suppliers, purchase orders.
- **Expenses**: categorized expense tracking.
- **Finance (derived, NOT a ledger)**: P&L, reconciliation, receivables/COD.
- **GST**: CA dashboard, GST invoicing, **GSTR-1 filing** (portal-ready, done).
- **CRM**: Customer 360, leads.
- **People (partial)**: staff profiles, ID verification, RBAC (7 roles), audit trail.
- **Platform**: JWT/RBAC auth, audit events, notifications, S3 storage, DB backups, AWS deploy.

The gap to "replace Tally" is primarily the **double-entry accounting core** plus payroll
and the remaining statutory GST/TDS pieces.

## Phased plan

Each phase is independently useful and de-risks the next. Phase 1 is the foundation
everything else posts into.

### Phase 1 — General Ledger + Vouchers (foundation) ← START HERE
- Chart of Accounts (groups + ledgers, Tally-style hierarchy)
- Double-entry Journal Entries (balanced Dr = Cr, immutable once posted)
- Voucher types: Journal, Payment, Receipt, Contra, Sales, Purchase, Debit/Credit note
- Ledger view, Day Book, Trial Balance
- **Auto-posting** from existing Sales / Purchase / Expense / Payment flows
- Financial-year handling + opening balances
- Statutory audit trail on all financial vouchers (MCA edit-log rule)

### Phase 2 — Financial statements + AP/AR + Banking
- Balance Sheet, Profit & Loss (from ledger), Cash Flow, Trial Balance
- Accounts Payable & Receivable subledgers with **aging**, vendor/customer statements
- Bank accounts + **bank reconciliation**
- **Tally data migration**: masters, opening balances, historical vouchers
- Parallel-run reconciliation reports vs Tally

### Phase 3 — Full GST + TDS
- GSTR-3B, GSTR-2A/2B reconciliation for **Input Tax Credit** (needs purchase GST capture)
- **e-invoicing (IRN/QR via IRP)** and **e-way bills** (if turnover crosses thresholds)
- **TDS/TCS**: deduction, challans, returns (26Q/27Q), certificates
- Builds directly on the Phase-1 GL and the existing GSTR-1 work

### Phase 4 — Payroll & HR
- Employee master (extends staff profiles), attendance, leave management
- Salary structure, payslips, salary run → posts to GL
- Statutory: **PF, ESI, Professional Tax, TDS on salary, Form 16**

### Phase 5 — Extended ERP
- Fixed assets & depreciation
- **Batch / expiry tracking + quality** (important for herbal/pharma products)
- Manufacturing / Bill of Materials (if production is in scope)
- Cost centers, budgeting
- Multi-warehouse / multi-location / multi-company (if branches exist)

### Phase 6 — Cutover
- Parallel run alongside Tally for 1–2 months
- Reconcile to the paisa (trial balance, GST, statements)
- Freeze Tally, cut over to Shifa ERP as system of record

## Key risks & considerations

- **Compliance accuracy is non-negotiable.** GST, TDS, PF/ESI, and financial-statement
  logic MUST be validated by the client's **CA / compliance advisor**. The engine can be
  built here, but statutory rules and edge cases need professional sign-off — incorrect
  filings carry legal consequences.
- **Scope is large** — a multi-phase program (several months). Phasing keeps it shippable.
- **Tally migration** (opening balances, ledgers, historical vouchers) is a project in
  itself; plan a parallel-run period before cutover.
- **Higher data-integrity bar** — financial data needs immutable audit trails, robust
  backups, and tight access control. Existing audit events, RBAC, S3 storage, and DB
  backups are a good starting point and must be extended to financial vouchers.
- **Stack is sufficient** — the Spring Boot modular monolith + Angular + MySQL + Flyway
  foundation scales to ERP scope without a rewrite.

## Status

- **Phase 1: IN PROGRESS** — spec being created (`.kiro/specs/general-ledger-accounting/`).
- Phases 2–6: planned (this document).
