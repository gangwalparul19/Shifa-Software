# Accountant — Quick-Start Guide

As an **Accountant** you track the money: reconciliation, COD collection, expenses, Profit & Loss, and
the general ledger. You can also approve orders.

## Where you land
After sign-in you land on the **Dashboard** (money/receivables KPIs — tiles link into Reconciliation).
Your 4 mobile bottom tabs are **Reconcile · Reports · Expenses · Orders**.

## Reconciliation — your main screen
Orders → **Reconciliation**:
- **Receivables:** customer dues + uncollected COD, oldest first.
- **Unsettled COD:** COD still to collect/remit.
- **Pending claims:** claim receivables (e.g. from a lost/redispatched parcel).
- **COD aging / SLA:** COD bucketed by age (0–7 / 8–15 / 16–30 / 30+ days); anything over SLA is flagged.
- **Courier remittance import:** upload the QuikShipX COD sheet (**CSV or Excel**). It matches each row
  to an order and settles it; for a delivery whose webhook was missed, it also marks the order
  Delivered + COD Collected from the row. **Preview (dry run) first**, then confirm.

## Approving orders
You can **approve** (or reject) orders from the order detail drawer — the same decision an admin makes.

## Expenses & P&L
Finance → **Expenses** (record expenses by category) and **Profit & Loss** (revenue vs expenses for a
period).

## General ledger (Accounting)
You can **post** double-entry vouchers and view the books:
- **Chart of Accounts**, **Voucher Entry** (manual vouchers + reversal), **Day Book**.
- **Trial Balance**, **Ledger Statement**, **Balance Sheet**, **Profit & Loss**, **Cash Flow**.
- Many vouchers auto-post (sales on approval, purchases, expenses, payment verification, COD collection).

## Reports
Reports → full **money & receivables** and **operations** reports plus sales/orders, all exportable to
Excel/PDF. Use the money tiles (outstanding, COD pending from courier, received, total sales).

## Golden rules
- Always **preview** a remittance import before committing it.
- Chase the oldest receivables and anything over the COD SLA first.
- RTO (not Cancel) is the correct reversal for a delivered/invoiced order — it raises a credit note.
