# Shifa OMS — Application Memory

> A durable, single-file reference to the whole application: what it is, how it is built, the
> roles, the end-to-end workflows, and where things live in the code. Keep this updated when the
> architecture changes. This document was generated from a code scan (not from the live/production
> system) and is the companion to `USER-MANUAL.md` and the role guides under `docs/role-guides/`.

---

## 1. What the application is

**Shifa OMS** is a dashboard-only **Order Management System** for *Shifa Herbal Remedies*. The public
storefront was retired and now runs on **Shopify**; Shopify orders are mirrored into the OMS
automatically via a webhook. The remaining scope is:

- An **admin dashboard** for the whole business (approvals, fulfilment, finance, GST, analytics).
- **Salesperson order entry** (a salesperson captures the customer's details and punches the order).
- **Role-based operations**: packing, payment verification, accounting/GST, team oversight.

The system runs the full order lifecycle (approval → packing → courier/in-house delivery → delivery
outcome → settlement), customer CRM, leads, inventory, procurement, returns, reconciliation, a full
double-entry **general ledger**, **GST** dashboards + returns filing, and real-time notifications.

---

## 2. Technology stack

| Layer | Technology |
|---|---|
| Backend | Java 21 (language level), Spring Boot 3.3.x — a **modular monolith** |
| Database | MySQL 8 with **Flyway** migrations (`ddl-auto: validate`) |
| Security | Spring Security + JWT (access + refresh tokens), role-based access control |
| Frontend | Angular (admin app) + shared `core`/`ui` libraries; Tabler light theme; brand green `#1F5D3F`; ApexCharts |
| Delivery | **QuikShipX** courier integration (live) + **in-house ("Ishika Enterprise")** delivery |
| Storefront | **Shopify** (orders imported via HMAC-verified webhook) |
| File storage | Pluggable: Local filesystem (dev), Database (`stored_files`), or **S3** (production) |
| Hosting | AWS EC2 (Nginx + systemd + MySQL on-instance + S3), HTTPS at the production domain |
| Mail | SMTP (Gmail); mock in dev — outbox-drained email on key events |
| PWA | Angular service worker (installable, offline-aware), Web Push (VAPID, config-gated) |

**Run commands (Windows/cmd — use explicit paths, NOT a tool cwd):**

- Backend (port 8080): `mvn -f "backend/pom.xml" -DskipTests spring-boot:run`
- Admin UI (port 4300): `npm --prefix frontend run start:admin`
- Backend tests: `mvn -f "backend/pom.xml" test`
- Admin build: `npm --prefix frontend run build:admin`
- Local DB: MySQL 8, DB `shifa_dashboard`.

---

## 3. Roles

The platform has **8 roles** (`com.shifa.oms.auth.Role`). `CUSTOMER` is storefront-only and never
reaches the staff shell. The 7 staff roles:

| Role | Purpose | Home after login |
|---|---|---|
| **ADMIN** | Full access: management, approvals, configuration, fulfilment, finance, GST | `/dashboard` |
| **SALESPERSON** | Order entry + leads + their own customers/reports (scoped to their own orders) | `/dashboard` |
| **TEAM_LEAD** | Read-only oversight of assigned salespeople + can punch orders on their behalf | `/dashboard` |
| **PACKING_USER** | Packing: scan, pack, handover, dispatch, pick-list, RTO | `/packing` |
| **PAYMENT_VERIFIER** | Verifies payment screenshots vs. amount for prepaid orders | `/payments` |
| **ACCOUNTANT** | Reconciliation/settlement, expenses, P&L, reports, ledger (post), COD aging | `/dashboard` |
| **CA** (Chartered Accountant) | Read-only finance + GST dashboard, GST filing & reconciliation, ledger (read-only) | `/ca/gst` |

**Scoping rules (security-critical):**
- A **salesperson** sees only orders/customers/leads/reports **they created**.
- A **team lead** sees **their team's** orders (assigned via `users.team_lead_id`) plus their own; a
  lead with no assigned team is scoped to *themselves only* (never "all").
- **Admin / Accountant / CA** are unscoped (see everything in their allowed areas).
- CA is **read-only** everywhere, except nothing — it cannot approve orders, run fulfilment, or
  mutate catalog/customer/order data; ledger post/reverse is ADMIN/ACCOUNTANT-only.

---

## 4. Order lifecycle (state machine)

Statuses (`com.shifa.oms.statemachine.OrderStatus`), initial = **PENDING_ADMIN_APPROVAL**:

```
PENDING_ADMIN_APPROVAL
   ├─ APPROVED ─ LABEL_GENERATED ─ PACKED ─ HANDED_TO_DELIVERY
   │                                             ├─(courier) COURIER_ASSIGNED ─ DISPATCHED ─ IN_TRANSIT ─ OUT_FOR_DELIVERY
   │                                             └─(in-house) DISPATCHED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED (manual)
   │        OUT_FOR_DELIVERY ─┬─ DELIVERED ─┬─ CLOSED
   │                          │             └─ COD_COLLECTED
   │                          ├─ CUSTOMER_REJECTED ─┬─ OUT_FOR_DELIVERY (retry)
   │                          │                     └─ RTO
   │                          ├─ DELIVERY_FAILED ───┬─ OUT_FOR_DELIVERY (retry)
   │                          │                     └─ RTO
   │                          └─ REDISPATCH (lost/damaged by courier)
   ├─ REJECTED (admin)  ──────────────► PENDING_ADMIN_APPROVAL (fix & resubmit)
   ├─ PAYMENT_REJECTED (payment verifier) ─► PENDING_ADMIN_APPROVAL (fix & resubmit)
   └─ CANCELLED (admin, any pre-delivery stage; also tells courier to abort)
```

Key rules:
- Transition **legality** lives in `OrderStatus` (illegal moves are rejected, state unchanged).
- Transition **authorization** (who may do it) lives in `statemachine/TransitionAuthority`.
- Every transition goes through the central `order/OrderWorkflowService.applyTransition(order, target, Actor)`:
  authorize → check legality → write status + one history row → audit → fire the `NotificationMatrix`.
- **Approval** can be done by **ADMIN or ACCOUNTANT**.
- **RTO** is the GST-correct "give up" path (raises a credit note); **CANCELLED** is excluded from GST
  outward supplies — never cancel a post-invoice order, use RTO.
- **PAYMENT_REJECTED** and **REJECTED** are both recoverable via "Fix & resubmit" (same order code,
  full history preserved).
- Both QuikShipX and in-house orders flow through the warehouse packing queue (label/AWB is allotted
  on approval but the order stays at **LABEL_GENERATED** / "Orders to Pack" until physically packed).

**Business-facing stage groups** (used by the Orders filter + salesperson view, 6 groups):
`PENDING_APPROVAL · PROCESSING · SHIPPED · DELIVERED · FAILED_RETURNED · CANCELLED` (+ a `REJECTED` group).

---

## 5. Backend modules (`com.shifa.oms.*`)

| Module | Responsibility |
|---|---|
| `auth` | Users, roles, JWT, login/refresh, staff profiles + ID verification, profile change requests, team assignment |
| `order` | Orders, order entry, pricing/discount engine, duplicate guard, on-behalf-of, suggestions (favorites/upsell), export |
| `statemachine` | `OrderStatus` lifecycle + `TransitionAuthority` |
| `dashboard` | Role-shaped dashboard summary, channel (Portal/Shopify/All) overview, teams overview, metrics |
| `product` | Product catalog, price bands (min/sale/MRP), per-product GST/HSN, images, stats, CSV import |
| `inventory` | Stock levels + movements, restock/adjust |
| `packing` | Packing queue, scan & move, handover, labels (print + bulk), pick-list, RTO, package count |
| `label` | Internal shipping label PDF (barcode = QuikShipX order id when published, else order code), 4-up A4 |
| `courier` | Courier companies, assignment, tracking template, shipping label |
| `quikshipx` | QuikShipX create/confirm/allot/track integration, outbox-driven, per-order retry, self-healing re-drive |
| `shopify` | Shopify order-import webhook (HMAC-verified), auto-approve, on/off switch, stuck-order recover |
| `invoice` | GST tax-invoice PDF (per-line discount/GST%, per-rate breakup, amount-in-words), invoice numbering |
| `payment` | Payment verification queue + verify/reject, duplicate-screenshot detection |
| `reconciliation` | Receivables, COD settlement, courier remittance import (CSV/Excel), COD aging/SLA |
| `reporting` | 17 report types (sales/orders/money/operations), Excel/PDF export |
| `finance` | Profit & Loss |
| `ledger` | Full double-entry general ledger: chart of accounts, vouchers, auto-posting, day book, trial balance, statements, balance sheet, P&L, cash flow |
| `gst` | GST dashboard (GSTR-3B style), rate/HSN/state breakup, export GST (18% IGST), GSTR-1 build, filing, reconciliation |
| `procurement` | Suppliers + purchase orders |
| `returns` | Returns/refunds, auto-return on RTO (credit note) |
| `crm` | Customer 360 (derived by mobile): profile, risk, tags, notes |
| `lead` | Lead pipeline (capture/transition/convert), follow-up reminders, reports |
| `performance` | Salesperson 360 + leaderboard, sales targets, team performance |
| `analytics` | Retention cohorts, revenue/demand forecast, configurable tiles |
| `insights` | Statistical insights engine (7 families: anomalies, low stock, RTO risk, courier scorecard, etc.) |
| `adminexception` | Admin Exception Center (approvals waiting, payment issues, failed deliveries, claims, insights) |
| `adminnotification` | In-app notifications (per-user/role), notification center |
| `announcement` | Staff announcement banners |
| `push` | Web Push (VAPID), config-gated |
| `notification` | NotificationMatrix (WhatsApp/email/in-app fan-out), dispatchers, outbox |
| `whatsapp` | Customizable one-tap WhatsApp message templates |
| `mail` | Email renderer + outbox drainer; daily/weekly consolidated report |
| `audit` | Audit events (who did what, when; field-level order-edit diffs) |
| `settings` | Company + GST config, logo, auto-approval, Shopify switch |
| `geo` | Delivery-state master list (`/api/states`) |
| `search` | Global search (command palette) |
| `agent` | AI/agent helpers |
| `platform` | Storage (Local/DB/S3), image compression, cross-cutting |
| `common` | Shared DTOs, exception handling (incl. 409 concurrency, 413 upload size), spreadsheet parser, Jackson IST config |

**Removed in the storefront pivot:** account, review, payment-coupon, public checkout/catalog/storefront-config.

---

## 6. Frontend navigation (admin app)

The shell (`shell/admin-shell.component.ts`) renders a **desktop left sidebar** (≥992px) and a
**mobile hamburger drawer + 4-tab bottom bar** (<992px). Nav links are role-gated per child; empty
groups are hidden. Nav groups:

- **Orders**: New Order, Approval Queue, Exception Center, Payments, Orders, Cancel Order, Deleted
  orders, Packing, Pick-list, Mark RTO, Reconciliation, Returns.
- **CRM**: Leads, Due follow-ups, Customers, Leaderboard, Salespeople, Team-wise Sales, Teams.
- **Catalog**: Products, Inventory.
- **Procurement**: Suppliers, Purchase Orders.
- **Analytics & Reports**: Reports, GST & Accounting, GST Filing, GST Reconciliation, Analytics,
  Shopify Sync, Insights, Team Performance.
- **Finance**: Expenses, Profit & Loss.
- **Accounting** (general ledger): Chart of Accounts, Voucher Entry, Day Book, Trial Balance, Ledger
  Statement, Balance Sheet, Profit & Loss, Cash Flow.
- **Account & Settings**: My Profile, Users, Profile approvals, Settings, Notifications,
  Announcements, WhatsApp templates, Audit Log, Backups.

**Bottom tabs by role:**

| Role | 4 bottom tabs |
|---|---|
| SALESPERSON | New Order · Orders · Customers · Products |
| PACKING_USER | Packing · Pick-list · Mark RTO · Orders |
| ACCOUNTANT | Reconcile · Reports · Expenses · Orders |
| ADMIN | Approvals · Orders · Products · Reports |
| PAYMENT_VERIFIER | Payments · Orders · My Profile |
| CA | GST · Reports · Finance · My Profile |
| TEAM_LEAD | Dashboard · Orders · Performance · My Profile |

---

## 7. Key cross-cutting behaviors

- **Authentication**: JWT access token (15 min) + 7-day refresh token. The frontend silently refreshes
  on a 401 and retries once; a genuinely expired session routes to `/login`. Public auth endpoints
  never receive a stale token. An admin can reset a user's password to a temporary one, forcing a
  change on next login (`/change-password`).
- **Timezone**: all displayed times are **IST (Asia/Kolkata)**. Backend serializes `LocalDateTime`
  with the `+05:30` offset; frontend renders via the `istDate` pipe.
- **Money/GST**: prices are **GST-inclusive by default** (toggle in Settings). Order totals round to
  the whole rupee. Minimum ₹100 must be collected upfront at order entry (full or partial; the
  balance is pay-on-delivery), and a payment screenshot is required when any amount is received.
- **Same-day duplicate guard**: a customer cannot have two same-day active orders for the **same
  product** (different items same day are allowed).
- **Auto-approval** (default OFF, admin-enabled): a fully-prepaid, low-risk, under-threshold order can
  auto-approve at entry.
- **Real-time**: Server-Sent Events (`/api/admin/events`) push live notifications to all staff roles;
  admins get a clickable "new order awaiting approval" toast + chime.
- **Pagination**: every list screen is paginated (per-table page size persisted in localStorage).
- **Export**: Orders and reports export to CSV/Excel; invoices, labels, GST reports to PDF.
- **Backups**: admin on-demand DB backup + history (`/backups`).

---

## 8. Integrations

- **QuikShipX** (courier, live): orders are created on punch, confirmed + allotted a tracking id/AWB
  on approval, and tracked by a polling job. Outbox-backed with a retry ladder and a self-healing
  re-drive for transient failures; per-order "Retry tracking ID" action for admins.
- **In-house delivery ("Ishika Enterprise")**: no courier; staff advance delivery status manually and
  settle COD on delivery. **Counter Sale** lead source forces in-house.
- **Shopify** (storefront): `orders/create` webhook (HMAC-verified) mirrors each order, maps line
  items by SKU, resolves payment split from Shopify amounts, and (optionally) auto-approves. There is
  an admin on/off switch and a Shopify Sync page to recover stuck orders.
- **WhatsApp / Email**: mock in dev. One-tap click-to-WhatsApp uses customizable templates; email
  fires on APPROVED/DISPATCHED/DELIVERED and for the daily/weekly consolidated report.

---

## 9. Where to look in the code

- Roles: `backend/.../auth/Role.java`
- Order lifecycle: `backend/.../statemachine/OrderStatus.java`, `TransitionAuthority.java`
- Central workflow: `backend/.../order/OrderWorkflowService.java`
- Routes + guards: `frontend/projects/admin/src/app/app.routes.ts`
- Navigation + bottom tabs: `frontend/projects/admin/src/app/shell/admin-shell.component.ts`
- Login redirects by role: `frontend/projects/admin/src/app/dashboard/dashboard.component.ts`
- DB migrations: `backend/src/main/resources/db/migration/` (versioned `V1..Vn`; never edit an applied one)

---

## 10. Deployment (reference only — do not run against production from here)

- Deploy script: `deploy/push-to-new-server.ps1` (build → upload JAR + admin bundle → DB backup →
  restart → reload Nginx). Server-side apply: `deploy/aws-apply.sh`.
- Storage in production: S3. DB: MySQL 8 on the same EC2 instance.
- Every deploy takes a pre-restart `mysqldump` backup. Migrations are additive and apply on restart.
