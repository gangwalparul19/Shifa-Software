# Shifa Herbal Remedies — Order Management System (OMS)

Internal **admin dashboard + order management platform** for Shifa Herbal Remedies. The public
storefront was retired (the store now runs on **Shopify**); this project is now a **dashboard-only**
back office: staff manage products, salespeople punch orders, and the order lifecycle (approval →
packing → courier → delivery → settlement) is tracked end to end.

> New to the repo? This README is the single source of truth for architecture, how to run things,
> and the important gotchas. Read it before scanning the codebase. A condensed "memory" for fast
> context lives in `.kiro/steering/project-memory.md` and is kept in sync with this file.

> **Current baseline (2026-11-03):** **Production is at V110** (deployed and verified on the server 2026-11-03 00:00 IST). It includes the customer-email switch (customer emails are off by default), Team-wise sales periods and export, the complete Leaderboard with Excel/PDF export, and the earlier announcement pinning and period selectors. See "Deployed to Prod (2026-11-03, V110)" at the end of this file. Older sections that say "production last documented at V73" or "not deployed" are historical. The supported production target is AWS EC2 (`15.252.230.73` SSH) with the canonical HTTPS URL `https://shifa.weblithic.online/` and Amazon S3 storage (`application-prod.yml` defaults to `STORAGE_PROVIDER=S3`). The unused OCI stub/SDK are removed. Current validation is **1071 backend tests, 0 failures** (full `clean package`, excluding `WhatsappTemplateEncodingIT` which needs a live MySQL), admin production build successful with the existing shell CSS budget warning, and frontend property tests **7 suites / 36 tests**. Dated deployment/build notes below are historical records; use the current baseline and `DEPLOYMENT.md` for operations.


| Layer      | Technology                                                                 |
|------------|----------------------------------------------------------------------------|
| Backend    | Java 21 (language level), Spring Boot 3.3.5 — modular monolith             |
| Persistence| MySQL 8, Spring Data JPA (Hibernate), Flyway migrations (`ddl-auto: validate`) |
| Security   | Spring Security + JWT (access/refresh), role-based (`@PreAuthorize`); public login/refresh/register calls are bearer-free and CORS permits the production admin origin `https://shifa.weblithic.online` |
| Frontend   | Angular 21 workspace — `admin` app + shared `core` & `ui` libraries         |
| Admin UI   | Tabler light theme (`@tabler/core`), Inter fonts, brand green `#1F5D3F`, ApexCharts |
| Docs / PDF | Apache POI (Excel), OpenPDF (invoices/labels), ZXing (barcodes)             |
| Cloud      | AWS EC2 (Ubuntu + self-hosted MySQL 8), Nginx/systemd, Amazon S3 file storage |

Integrations are configuration-driven: Shopify webhooks and QuikShipX courier flows are production-capable and HMAC-protected; WhatsApp, email, and payment modes remain selectable between MOCK/SANDBOX and live providers.

---

## 2. Repository Layout

```
Shifa-Software/
├─ backend/                     # Spring Boot modular monolith
│  ├─ src/main/java/com/shifa/oms/   # feature modules (see §4)
│  ├─ src/main/resources/
│  │  ├─ application.yml              # base config
│  │  ├─ application-local.yml        # local profile (MySQL localhost, shifa_dashboard)
│  │  ├─ application-prod.yml         # prod profile (env-driven)
│  │  └─ db/migration/                # Flyway V1..V105 (see §6)
│  └─ pom.xml
├─ frontend/                    # Angular 21 workspace
│  ├─ angular.json                    # projects: admin, core, ui
│  ├─ package.json                    # npm scripts (see §3)
│  └─ projects/
│     ├─ admin/                       # the admin dashboard app (port 4300)
│     ├─ core/                        # shared services/models (ApiClient, guards, Role)
│     └─ ui/                          # shared UI component library
├─ deploy/                      # AWS deployment assets (nginx, systemd, env example, scripts)
├─ DEPLOYMENT.md                # canonical AWS EC2 deployment guide
├─ ROADMAP.md                   # feature backlog / future enhancements
└─ README.md                    # you are here
```

---

## 3. Running Locally

**Prerequisites:** JDK 21+ (Java 25 runtime works — see §8 gotchas), Maven, Node 20+, MySQL 8
running on `localhost:3306`, and a database named `shifa_dashboard`.

Create the DB (empty — Flyway builds the schema on first run):
```sql
CREATE DATABASE shifa_dashboard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### Backend (port 8080)
```
mvn -f "backend/pom.xml" -DskipTests spring-boot:run
```
- Default profile is `local` (MySQL `root`/`root@123`, DB `shifa_dashboard`).
- On startup Flyway applies V1..V27 and local `@Profile("local")` seeders top up demo data
  (they no-op because V22 already seeds it). V27 adds a large NOW()-relative test dataset on top.

### Admin UI (port 4300)
```
npm --prefix frontend run start:admin
```
Then open http://localhost:4300/.

### Seeded logins (demo data)
| Username     | Password    | Role          |
|--------------|-------------|---------------|
| `admin`      | `admin123`  | ADMIN         |
| `accountant` | `admin123`  | ACCOUNTANT    |
| `sales1`     | `admin123`  | SALESPERSON   |
| `sales2`     | `admin123`  | SALESPERSON   |
| `packer`     | `packer123` | PACKING_USER  |

**V27 test data (large seed):** 20 more salespersons `sales01`..`sales20`, plus `accountant2`,
`packer2`, `admin2` — **all with password `admin123`**. See `docs/test-data-guide.html` for the full
login list and a per-role navigation tour.

### Useful npm scripts (`frontend/`)
- `start:admin` — serve the admin app (dev, watch)
- `build:admin` — production build (`dist/admin`)
- `build:core`, `build:ui` — build the shared libraries
- `test` — Angular CLI unit-test runner (`ng test`); `test:pbt` — Jest/fast-check property tests. No Playwright/Cypress e2e runner is registered in this workspace.

---

## 4. Backend Modules (`com.shifa.oms.*`)

Modular monolith — one package per bounded context. Kept modules after the dashboard-only pivot:

| Module            | Responsibility / key endpoints |
|-------------------|--------------------------------|
| `auth`            | JWT login/refresh, users, RBAC, `SecurityConfig`, seeders. `/api/auth`, `/api/admin/users` |
| `order`           | Salesperson order entry + search + detail + invoice; order-entry product picker. `/api/orders` (incl. `GET /api/orders/products`) |
| `statemachine`    | `OrderStatus` lifecycle + legal transitions (INITIAL/PENDING_ADMIN_APPROVAL → … → DELIVERED/COD_COLLECTED/RTO/etc.) |
| `product`         | Admin product CRUD + categories + CSV import; per-product monthly sales stats. `/api/admin/products` (+ `GET /{id}/stats`), `/api/admin/categories` |
| `inventory`       | Stock levels + movements (RESTOCK/SALE/RETURN/ADJUSTMENT). `/api/admin/inventory` |
| `packing`         | Barcode Scan & Move preview/confirmation + handover (name/phone) + multi-pack + bulk labels. `/api/packing` (incl. `/scan-preview`, `/{id}/handover`, `/{id}/packages`) |
| `payment`         | **Payment verification** (PAYMENT_VERIFIER): review prepaid screenshots, verify/reject. `/api/payments/queue`, `/api/payments/{id}/verify`, `/api/payments/{id}/reject` |
| `courier`         | Courier assignment (mock), tracking, webhooks, shipping labels. `/api/track`, `/api/webhooks/courier`, `/api/admin/labels/shipping` |
| `label`           | Internal label PDFs. `/api/admin/labels/internal` |
| `invoice`         | Per-order invoice PDF (plain + GST tax invoice) + invoice numbering |
| `reconciliation`  | COD/settlement, receivables. `/api/recon` |
| `reporting`       | 17 report views — Sales, Orders, **Money & Receivables** (PAYMENTS, OUTSTANDING dues, COD_REMITTANCE) and **Operations** (EXPENSES, PURCHASE_ORDERS, RETURNS, STOCK; ADMIN/ACCOUNTANT only) — with Excel / PDF exports. `/api/reports/{type}` |
| `finance`         | Expenses + Profit & Loss. `/api/admin/expenses`, `/api/admin/finance` |
| `procurement`     | Suppliers + purchase orders. `/api/admin/suppliers`, `/api/admin/purchase-orders` |
| `returns`         | Order returns/refunds. `/api/admin/returns` |
| `crm`             | Customer records derived from orders + **Customer 360**: list/detail (`/api/admin/customers`), profile/risk/tags/notes (`/{mobile}/profile`, `/{mobile}/risk`, `/{mobile}/notes`, `/{mobile}/tags`) |
| `performance`     | **Salesperson 360 / performance owners**: leaderboard + per-owner performance detail + monthly sales targets. The owner directory includes ADMIN (shown as **Admin**), TEAM_LEAD, and SALESPERSON rows; admins stay outside team assignment. Complete XLSX/PDF exports support (`TODAY`, `YESTERDAY`, `WEEK`, `MONTH`, `OVERALL`) with selected-period orders/leads and owner-role labels. `/api/admin/salespeople/performance`, `/api/admin/salespeople/{id}/performance`, `/api/admin/salespeople/performance/export`, `/api/admin/salespeople/{id}/performance/export`, `/api/admin/salespeople/targets`. **Team-lead performance** (team KPIs + leaderboard + lead-source conversion): `GET /api/team/performance` (TEAM_LEAD+ADMIN) |
| `analytics`       | Customer **retention** cohorts, revenue/demand **forecast** and delivery performance, plus **growth analytics** (overview, customer segments, product performance, operations, ranked key pointers) and CSV **data downloads**. ADMIN only. `/api/admin/analytics/{retention,forecast,delivery-performance,overview,customers,products,operations,pointers,datasets}` and `/api/admin/analytics/datasets/{key}/export` |
| `announcement`    | Staff announcement banners. `GET /api/announcements` (staff), admin CRUD `/api/admin/announcements` |
| `push`            | Browser Web Push (VAPID, config-gated). `/api/notifications/push/{public-key,subscribe,unsubscribe}` |
| `dashboard`       | Metrics + SSE event stream. `/api/admin/metrics`, `/api/admin/events` |
| `adminexception`  | Admin-only read-only Exception Center aggregating approval, payment, failed-delivery, QuikShip failure, courier-claim, and urgent-insight work items. `/api/admin/exceptions` |
| `adminnotification`| Durable staff alerts (role/user-addressed). `/api/notifications` (staff), `/api/admin/notifications` |
| `audit`           | "Who did what" audit trail. `/api/admin/audit` |
| `settings`        | Company + GST + invoice settings (GST on/off). `/api/admin/settings` |
| `geo`             | Delivery-state master list for the order-entry typeahead. `GET /api/states` (staff), admin CRUD `/api/admin/states` |
| `search`          | Global admin search. `/api/admin/search` |
| `agent`           | Salesperson lookup helpers. `/api/agent` |
| `notification`    | WhatsApp/outbox notifications (mock), transactional outbox drainer |
| `mail`            | SMTP/mock email. `/api/admin/mail` |
| `platform`        | Cross-cutting: `LOCAL`/`DB`/`S3` storage, backups, outbox infrastructure. Production `application-prod.yml` defaults to AWS S3; there is no OCI provider. `/api/admin/backups` |
| `common`          | Shared errors, paging, `GlobalExceptionHandler` |

**Removed in the dashboard-only pivot:** `account` (customer login/cart/wishlist/addresses),
`review` (product reviews + moderation), `payment` (online payment sandbox), `coupon` (discount
codes), and the public `CheckoutController` / `CatalogController` / `CatalogCategoryController` /
`StorefrontConfigController`.

### Order entry note
Salespeople create orders via `POST /api/orders` (customer details are captured by the salesperson).
The order-entry product picker uses `GET /api/orders/products` (published products, `?q=` filter),
scoped to `SALESPERSON`/`ADMIN` — this replaced the retired public catalog endpoint. The New Order
form is a compact, mobile-first layout (paired fields per row) with a **fuzzy state typeahead** fed by
`GET /api/states` (states managed on the Settings "Delivery states" card) and an optional free-text
**order note** (`orders.notes`, ≤1000 chars) saved with the order and shown on the order detail.

The form is now a **guided 4-step wizard** (Customer → Items → Payment → Review) with a progress
stepper and per-step validation, plus these order-entry conveniences: an optional **alternate contact
number** (`orders.alternate_mobile`), **PIN-code auto-fill** (typing a 6-digit pincode pre-fills empty
City/State from the free key-less India Post API — best-effort, degrades to manual entry when offline),
and the running/created total **rounded to the nearest whole rupee** (`Money.roundToWholeRupees`, e.g.
₹2679.99 → ₹2680; GST-safe as prices already include GST).

The form remembers the salesperson's last non-OTHER lead source as a safe preference, keeps customer/payment
values isolated between orders, and supports a persisted **Quick** single-screen mode with thumb-sized quantity
steppers, decimal numeric keyboards, required upfront-payment shortcuts, sticky actions, draft recovery, and
server-side last-order prefill. Reorder mode also carries forward the previous order's contact/source/GST details
without copying payment or line-item state. Offline creation is intentionally disabled because every new order now
requires upfront collection and a payment screenshot.

The Orders **list** filters by a **grouped status selector** (both a quick tab strip and the advanced-panel
dropdown) instead of raw statuses: `OrderStatusGroup` exposes 6 QuikShip-aligned stages — Pending Approval,
Processing, Shipped, Delivered, Failed/Returned, and Cancelled. Filtering is server-side via
`GET /api/admin/orders?statusGroup=…`; aliases keep older saved views/deep links working.

---

## 5. Admin App Pages (`frontend/projects/admin/src/app`)

Routing in `app.routes.ts`, shell/nav in `shell/admin-shell.component.ts`. Guards enforce roles
(`staffGuard`, `adminOnlyGuard`, `salespersonGuard`, `accountantGuard`, `packingGuard`,
`paymentVerifierGuard`).

| Route | Page | Access |
|-------|------|--------|
| `/dashboard` | KPIs, sparklines, welcome hero | all staff |
| `/approval-queue` | Approve/reject pending orders | ADMIN |
| `/exceptions` | Unified admin work list for approval, payment, delivery, claim, and urgent insight exceptions | ADMIN |
| `/orders`, `/orders/new` | Orders list / new order entry | staff / SALESPERSON+ADMIN |
| `/products` | Catalog (manage: ADMIN) | ADMIN + SALESPERSON (read-only) |
| `/inventory` | Stock | ADMIN |
| `/customers` | CRM + Customer 360 drawer (metrics, delivery-risk, tags, notes, order history; SALESPERSON sees only their own) | ADMIN + ACCOUNTANT + SALESPERSON |
| `/returns` | Returns/refunds | ADMIN+ACCOUNTANT |
| `/notifications`, `/audit` | Alerts / audit log | ADMIN |
| `/announcements` | Post/hide/delete staff announcement banners | ADMIN |
| `/suppliers`, `/purchase-orders` | Procurement | ADMIN |
| `/expenses`, `/finance/pnl` | Finance | ADMIN+ACCOUNTANT |
| `/packing` | Orders-to-pack, awaiting handover, QuickShip status, and in-house delivery queues; Scan & Move, label printing, handover, pagination | PACKING_USER+ADMIN |
| `/payments` | Payment verification dashboard — review prepaid screenshots, verify/reject | PAYMENT_VERIFIER+ADMIN |
| `/team` | Assign salespeople to a team lead (drives team-scoped order visibility) | ADMIN |
| `/team-performance` | Team performance rollup — clickable Salespeople KPI, team-scoped salesperson list, and profile/performance drill-down (lifetime, today, last week/month, delivery/COD/leads, daily activity, recent orders). The orders list, salespeople table and coaching list are all paginated | TEAM_LEAD+ADMIN |
| `/reconciliation` | COD settlement | ADMIN+ACCOUNTANT |
| `/reports` | Grouped report catalogue (Sales / Orders / Money & Receivables / **Operations**: expenses, purchase orders, returns, stock) — incl. a **Finance** tab: outstanding dues chase list, COD pending from courier, daily payments; Excel/PDF export | ADMIN+ACCOUNTANT |
| `/analytics` | Ten tabs: Overview (with ranked key pointers), Customers, Retention, Products, Acquisition, Operations, Delivery, Forecast, Targets & Incentives, Data (CSV downloads). `?tab=` selects a tab | ADMIN |
| `/settings`, `/users` | Config + staff users | ADMIN |
| `/salespeople` | Salesperson 360 performance-owner directory: onboarding/ID verification plus Admin, Team Lead, and Salesperson KPIs, sorting, per-owner performance drawers, and monthly targets. Admin owners stay outside team assignment. Team and individual XLSX/PDF exports support Overall, Today, Yesterday, This Week, and This Month; XLSX contains full selected-period details and PDF contains KPI cards, graphs/charts, and detail tables | ADMIN |

The shell hamburger nav groups links under **collapsible, collapsed-by-default named groups** (accordion): only
"Shifa Dashboard" is standalone; the rest sit under **CRM** (Leads, Due follow-ups, Customers, Salespeople),
**Analytics & Reports** (Reports, Analytics, Insights), and **Account & Settings** (My Profile, Settings, Users,
…). The role-aware bottom tab bar is unchanged.

The current route tree also includes `/tasks`, `/meetings`, `/crm-dashboard`, `/crm-settings`, `/packing/rto`,
`/packing/pick-list`, `/settlements`, `/remittance-report`, `/whatsapp-templates`, `/shopify-sync`,
`/order-cancellation`, `/deleted-orders`, `/ca/gst` plus filing/reconciliation pages, the accounting pages under
`/accounting/*`, and `/teams-overview`, `/delivery-partners`, `/leaderboard`. `/channel-dashboard` is retained
only as a redirect to `/dashboard`.

### Mobile-first UI redesign (spec `mobile-ui-redesign`)
The admin app was redesigned mobile-first to a client wireframe (`docs/wireframe.jpeg`), reusing the
Tabler theme + Shifa green + ApexCharts. The shell is a **solid dark-green banner top bar + a
top-bar hamburger (full role-filtered menu, incl. the Shifa dashboard) + a persistent role-aware
bottom tab bar** carrying the four most-used destinations per role (Salesperson: New Order/Orders/
Customers/Products; Packer: Packing/Handover/Dispatch/Orders; Accountant: Reconcile/Reports/Expenses/
Orders; Admin: Approvals/Orders/Products/Reports). Every screen is single-column at 360px, uses
cards over wide tables below 768px, KPI tiles (green icon chip + value + delta), colored status
pills, ≥44px touch targets, real product images (served from `admin/public/products/`), and the
order/product detail + all operational/config/auth pages follow the same language. The Team Lead dashboard keeps **New Order** as a compact header action, removes duplicate Team Orders action banners, and renders KPI cards three-up on mobile. Presentation-only
— it reuses existing endpoints (plus the additive `GET /api/admin/products/{id}/stats` and the order
line `imageKey` / `discountAmount` fields).

### Customer records & internal CRM (FEATURE-ROADMAP §1)
The `/customers` drawer is a **Customer 360**: derived delivery-reliability **risk score**
(LOW/MEDIUM/HIGH from delivered vs failed/RTO/rejected history), metrics (delivered / failed /
in-flight / outstanding), products bought, status breakdown, staff-managed **segment tags** and a
**notes timeline**, alongside the order history. Backed by a separate `CustomerInsightService` +
`CustomerCrmController` (the original list/detail `CustomerService`/`CustomerController` are unchanged),
persisted via `V34` (`customer_tags`, `customer_notes`). The New Order form shows a **prepaid nudge**
when a MEDIUM/HIGH-risk customer's mobile is entered (`GET /api/admin/customers/{mobile}/risk`).
Salesperson scoping applies throughout (a salesperson only sees customers from their own orders).

### Staff mobile & UX (FEATURE-ROADMAP §8)
- **Installable PWA + connectivity status (8.1)** — Angular service worker (`ngsw-config.json`),
  `manifest.webmanifest`, install/update prompts, and an offline chip. New order creation is intentionally
  blocked offline because the current policy requires upfront collection and a payment screenshot; no offline
  order queue is active.
- **Phone-camera barcode scan (8.2)** — `CameraScannerComponent` uses the native `BarcodeDetector`
  (Code 128) so the packer can scan the internal label with a phone camera; a **Camera** button on
  `/packing` feeds the decoded order code into the existing scan flow (graceful fallback when unsupported).
- **Web push (8.3, config-gated)** — VAPID push via `nl.martijndwars:web-push`, **no-op unless
  `app.push.vapid.*` keys are set**. `push_subscriptions` (`V36`), `PushController`, and a best-effort
  hook off `StaffNotificationDispatcher`; the frontend opt-in lives in the notification bell (`SwPush`),
  and notification taps deep-link to the order.
- **Staff announcement banners (8.4)** — admins post notices at `/announcements` (`staff_announcements`,
  `V35`); every signed-in staff member sees active banners in the shell (dismissible per-user).

### Packing page redesign
The `/packing` page (the most-used floor screen) was rebuilt to match the rest of the app: KPI tiles
(awaiting pack / handover / dispatch), a hero **Scan & Move** control that opens the phone camera, and a
manual/handheld input that submits on Enter. `POST /api/packing/scan-preview` resolves the order code without
mutation and returns the current status plus the server-derived next action (Pack / Handover / Dispatch / none);
the packer then explicitly confirms the move, while the final request still enforces role/state checks and detects
concurrent changes. The work queues are rendered as a **table** (Order ID / Customer / Price / Order date / Salesperson)
with **clickable rows → `/orders?q={code}`** and per-row primary action + Print label. The queue DTO gained salesperson
name + order date (`PackingQueueRow`), rows are **DESC by order date**, and focus uses `preventScroll` so opening the
page no longer jumps to the bottom.

### Analytics, insights & reporting (FEATURE-ROADMAP §6)
`/analytics` (ADMIN) is backed by the `performance` + `analytics` modules. The original tabs are below; the growth tabs added on 2026-11-01 are described in the last section of this file:
- **Sales targets (6.1)** — per-salesperson monthly targets (`sales_targets`, `V38`) with achieved vs
  target progress, set/edit from the tab and surfaced on Salesperson 360.
- **Retention (6.3)** — repeat-customer cohorts (new vs returning, repeat rate) from order history.
- **Forecast (6.5)** — next-period revenue + product-demand projection from trailing sales.
- Dashboard tiles are user-configurable (show/hide, persisted in `localStorage`, 6.4).
**Salesperson 360 / performance owners** (leaderboard + per-owner KPIs: orders, revenue, conversion, delivery success,
target progress) lives on the `/salespeople` page so admins can track and compare team performance. The
page includes Admin owners alongside Team Leads and Salespeople; admins remain outside team assignment.
The directory and each salesperson's Performance drawer can export the selected period (`Overall`,
`Today`, `Yesterday`, `This Week`, or `This Month`) as XLSX or PDF. XLSX uses separate Summary,
Daily Activity, Orders, and Leads sheets and includes every matching order; PDF includes branded KPI
cards, a daily sales chart, and the selected-period detail tables. Period boundaries are resolved
server-side in Asia/Kolkata so exports do not depend on the administrator's browser timezone. Normal admin punches
appear under the Admin owner; orders punched on behalf of a salesperson or Team Lead remain attributed
only to that selected owner.

### Salesperson/admin productivity and safety wave (no migration)
The current wave reduces taps for salespeople and gives admins a single, safer work queue:
- **Direct customer actions:** Orders and Customers list rows provide one-tap Call and WhatsApp actions on mobile and desktop; due follow-up cards provide Call, WhatsApp, and Work lead actions. Existing server-managed WhatsApp templates remain the message source.
- **Task-oriented follow-ups:** the existing My Day, due-follow-up, win-back, and reorder-due data is presented as actionable work without inventing a persisted task-completion model. Persisted assignment/snooze/completion remains deferred until a task schema is approved.
- **Admin Exception Center:** `GET /api/admin/exceptions` and `/exceptions` combine pending approvals, payment verification, failed/refused deliveries, recorded QuikShip failures across portal and Shopify orders, unsettled courier claims, and non-dismissed WARNING/DANGER insights. The admin dashboard's **Stuck shipments** KPI opens the filtered **QuikShip failures** view with the exact same all-source failure population, while Shopify Sync remains dedicated to Shopify recovery. The center now has category/source filter chips, visible matching counts, responsive exception rows, and persistent client-side pagination. It is ADMIN-only and read-only; actions open the owning page so existing authorization, confirmation, workflow, and GST safeguards still apply.
- **Safe bulk actions:** `POST /api/admin/orders/bulk-preview?action=APPROVE|MARK_PACKED|LABELS` returns eligible/ineligible rows before confirmation. Final bulk operations still re-read each order, enforce the state machine, and return partial success/skip reasons; previews never reserve orders.
- **Concurrency safety:** optimistic-lock failures now return HTTP 409 with code `CONCURRENT_UPDATE` and a refresh/retry message instead of a generic 500. The Orders UI treats lifecycle mutations pessimistically and explains when another operator changed an order.
- **Order history and manual delivery:** the order drawer keeps the friendly grouped timeline and adds an ADMIN-only raw action history showing from/to status, actor, source, and timestamp. In-house failed/refused updates require a reason; the UI explains retry versus RTO and the GST-safe credit-note path.
- **Contextual help and role homes:** help tips explain bulk previews and in-house/RTO semantics, while the admin dashboard has a direct Exception Center CTA and existing role-shaped dashboards keep each role's primary work visible first.
- **Verification:** the current local remediation worktree recorded **1042 backend tests, 0 failures**, frontend property tests **6 suites / 21 tests**, and a successful admin production build with the existing shell CSS budget warning. This local wave adds V101–V105 plus the selected C6/E/F changes and is **not deployed**. Source is V105; production remains at the last documented verified V73 until explicitly deployed and verified with Flyway. The canonical production URL is `https://shifa.weblithic.online/`.

---

## 6. Database & Migrations

- Schema is owned by **Flyway** (`backend/src/main/resources/db/migration`). Hibernate is
  `ddl-auto: validate` — **never** let Hibernate manage DDL.
- **Never edit an applied migration.** Add a new versioned migration instead.
- Local + prod + deploy all target the **`shifa_dashboard`** database.

Migration history:
- `V1`..`V20` — original build (orders, products, users, courier, invoice, inventory, reporting,
  settings, plus storefront features added along the way).
- `V21__drop_storefront_tables.sql` — drops storefront-only tables (`customer_accounts`,
  `customer_addresses`, `product_reviews`, `coupons`, `payment_transactions`, `wishlist*`,
  `customer_cart_items`, `cart_items`). Keeps `categories` and the storefront-added columns on
  `orders`/`products`/`users` (still mapped by retained entities).
- `V22__seed_demo_data.sql` — self-contained demo dataset (runs in ALL profiles): 13 products +
  images, 5 staff users, 5 suppliers, 5 POs, 15 orders across the full lifecycle with line items /
  status history / payments, courier records, receivables, stock movements, returns, expenses,
  notifications, audit events, and GST settings.
- `V23` — `orders.lead_source`/`lead_source_note`/`customer_email` (+ index). `V24` —
  `admin_notifications.recipient_role`/`recipient_user_id` (+ index). `V25` — `leads` +
  `lead_status_history` tables. `V26` — `insights` table. All additive/nullable, safe on V22.
- `V27__seed_test_data.sql` — **large NOW()-relative TEST/DEMO seed** layered on top of V22 (runs in
  ALL profiles, auto-applies on deploy). Non-colliding IDs (users 101–123, orders 1000–1119 as
  `SHR-5001`..`SHR-5120`, couriers 2–4, suppliers 10–14, POs 10–14). Seeds 23 more users (20
  salespersons + accountant2/packer2/admin2, **all password `admin123`**), 120 orders across the full
  lifecycle (~56 customers, some repeat), 240 line items, ~1k status-history rows, payments,
  receivables (COD outstanding + a lost claim), 142 stock movements (many in the last 30 days), 50
  leads with due/overdue follow-ups, 5 suppliers, 5 POs, 21 monthly expenses, notifications and audit
  events. Additive and safe — won't disturb existing data. See `docs/test-data-guide.html`.
- `V28` — `stored_files` (DB-backed binary store for payment screenshots; demo profile only — production uses S3).
- `V29` — `orders.notes` (optional ≤1000-char order note captured at order entry) + `delivery_states`
  (name UNIQUE, active, sort_order), seeded once with 28 states + 8 UTs. Powers the New Order state
  typeahead (`GET /api/states`) and the admin Settings "Delivery states" manager (`/api/admin/states`).
  Additive/nullable, safe on V22.
- `V30` — removes seeded admin notifications.
- `V31__staff_profiles_verification.sql` — **staff onboarding & salesperson ID verification**: adds
  profile + verification columns to `users` (`date_of_birth`, `address`, `joined_on`, `id_proof_type`,
  `id_proof_number`, `id_proof_key`, `verification_status` NOT NULL DEFAULT 'PENDING',
  `verification_note`, `verified_at`, `verified_by`) + index `ix_users_role_verification`. Backfills
  all existing rows to `VERIFIED` so current logins aren't flagged. Additive/nullable, safe on V22/V27.
  Powers `/api/admin/staff` and the admin **Salespeople** directory. The uploaded ID document lives in
  the pluggable `StorageService` (only `id_proof_key` is persisted).
- `V32__staff_profile_image.sql` — adds `users.profile_image_key` (staff profile photo; images are
  server-side compressed before storage via `ImageCompressor`).
- `V33__staff_profile_change_requests.sql` — `staff_profile_change_requests` (self-service "My Profile"
  edits queued for admin approval; nothing on `users` changes until approved).
- `V34__customer_crm_tags_notes.sql` — `customer_tags` + `customer_notes` (keyed by `customer_mobile`),
  the first persisted per-customer data behind the Customer 360 tags/notes (FEATURE-ROADMAP §1).
- `V35__staff_announcements.sql` — `staff_announcements` (admin-posted banners shown to all staff,
  FEATURE-ROADMAP §8.4).
- `V36__push_subscriptions.sql` — `push_subscriptions` (browser Web Push endpoints + keys per staff
  user; sending is gated on VAPID config, FEATURE-ROADMAP §8.3).
- `V37__fix_future_order_dates.sql` — one-time data fix: clamps any `orders.created_at` set in the
  future back to now (bad seed/test dates), so the DESC-sorted lists and reports read correctly. No
  schema change.
- `V38__sales_targets.sql` — `sales_targets` (per-salesperson monthly revenue target) behind the
  Analytics **Sales targets** tab + Salesperson 360 progress (FEATURE-ROADMAP §6.1).
- `V39`..`V42` — **client roadmap wave** (see `docs/PRODUCT-AUDIT-AND-ROADMAP.md`), all additive/nullable:
  `V39` `orders.alternate_mobile` (optional 2nd contact for failed-delivery follow-up); `V40`
  `orders.handover_name`/`handover_phone` (who took the parcel, captured via a handover popup); `V41`
  `orders.package_count` DEFAULT 1 (multi-pack — label prints N copies); `V42`
  `orders.payment_verification_status`/`_by`/`_at`/`_note` (new **PAYMENT_VERIFIER** role verifies
  prepaid payment screenshots via a dedicated `/payments` dashboard — an additive layer that does NOT
  touch the order state machine).
- `V43__team_lead.sql` — **TEAM_LEAD role**: adds `users.team_lead_id` (nullable self-FK → `users.id`,
  `ON DELETE SET NULL`, `ix_users_team_lead`) recording a salesperson's team lead. A team lead gets team-scoped
  order visibility, can punch orders themselves, and admins assign salespeople on `/team` (`/api/admin/team`).
  Additive/nullable.
- `V44__whatsapp_templates.sql` — `whatsapp_templates` (active, ordered, customizable one-tap customer-message
  templates). **ADMIN / ACCOUNTANT / TEAM_LEAD** manage templates at `/whatsapp-templates`; staff use active
  templates from order/customer screens. `V45` enriches initial copy, `V46` replaces unreliable 4-byte emoji with
  WhatsApp-Desktop-safe basic-plane symbols, and `V47__whatsapp_confirm_order_summary.sql` adds `{orderSummary}` to
  the confirmation template — itemized lines, order total, amount paid, and any COD balance.
- `V48__rename_courier_lost_to_redispatch.sql` — terminology/status migration: converts historic order and status-
  history values to `REDISPATCH`, updates known user-facing admin-notification text and JSON outbox status/template
  payloads, and ensures immutable V22/V27 seed values also finish as `REDISPATCH`. The existing
  `CLAIM_RECEIVABLE`, cleared customer outstanding, and claim-required alert semantics are unchanged.
- `V49`..`V64` — GST/product/courier/in-house-delivery and failed-delivery-recovery additions.
- `V65__order_payment_screenshots.sql` — **multiple payment proofs per order**: new
  `order_payment_screenshots` (order_id, `storage_key` UNIQUE, filename, content_type, byte_size,
  sort_order, created_at) plus a backfill of every existing `orders.payment_screenshot_key` as the
  order's first proof. Additive and backward compatible: `orders.payment_screenshot_key` is KEPT and
  still holds the primary proof, so the screenshot-required rule, the `paymentScreenshotAvailable`
  projections, and the original `GET /api/orders/{id}/payment-screenshot` endpoint are unchanged.
  **V66`..`V100** — payment rejection/rework, international/export orders, Shopify import/auto-approval, channel and ledger/accounting extensions, settlement/bank-account support, and MFA enablement.
  **V101__auth_session_reset_and_mfa_ciphertext.sql** — A2–A4 security hardening: adds per-user JWT session-version revocation, reset-credential expiry, and AES-GCM MFA ciphertext storage. Legacy MFA plaintext rows are encrypted by the startup backfill using the externally supplied `MFA_ENCRYPTION_KEY`; production fails closed when the key is missing.
  **V102__authenticated_sessions.sql** — server-authoritative sliding idle sessions: hashed per-login session ids, activity timestamps, revocation binding, and indexes.
  **V103__settlement_integrity.sql** — Settlement Center allocation target keys/reasons/date-default metadata plus allocation indexes/uniqueness.
  **V104__payment_utr_normalization_and_preflight.sql** — canonical payment UTR values, historical duplicate preflight evidence, and a conditional receiving-account/UTR uniqueness boundary.
  **V105__outbox_claim_leases.sql** — fenced pending-row leases, expiry recovery, and the composite due-claim index for multi-instance integration drainers.
  **V106__query_performance_indexes.sql** — five composite indexes for the hottest order, payment and status-history filters. **V106 is the current highest migration.**

> Fresh DB required: because V22 seeds with explicit IDs, start against an **empty**
> `shifa_dashboard`. If a half-migrated DB exists, drop & recreate it before starting.

---

## 7. History: the Dashboard-Only Pivot

The project began as a full e-commerce + OMS platform (storefront PWA + admin). The client moved the
storefront to **Shopify**, so the storefront app and all storefront-only backend modules were
removed on the `dashboard-only` branch. What remains is the admin dashboard and salesperson order
entry (customer details are entered by the salesperson, so customer capture stays valid). DB cleanup
is done via the additive `V21` drop migration (never by editing old migrations).

---

## 8. Environment Gotchas (important)

- **Windows / cmd shell.** A PowerShell `cd "…";` prefix can be injected that cmd can't parse — do
  **not** rely on a tool `cwd` parameter. Run from the workspace root with explicit paths
  (`mvn -f "backend/pom.xml" …`, `npm --prefix frontend …`).
- **Exit codes spuriously return -1** even on success. Judge by printed output (BUILD SUCCESS, test
  summary, "Application bundle generation complete"), not the exit code.
- **Java 25 runtime** (targets Java 21): Mockito cannot mock concrete classes on this JVM — tests
  use real instances / recording subclasses instead of mocked repos.
- **MySQL 8** is at `C:\Program Files\MySQL\MySQL Server 8.0\bin\` (not on PATH); local creds
  `root` / `root@123`, DB `shifa_dashboard`.
- **Two-constructor Spring services** (a primary + a test one taking a `Clock`) fail startup with
  "No default constructor found" — fix by adding `@Autowired` to the primary constructor.
- **Long-running commands** (dev server, watchers) must be started as background processes, not
  blocking shell calls.

---

## 9. Deployment

Hosted on **one AWS EC2 instance** (Ubuntu + self-hosted MySQL 8, Nginx reverse proxy, systemd, Amazon S3). The
canonical production URL is **`https://shifa.weblithic.online/`**; `15.252.230.73` is the current SSH address,
not the client URL. Admin app is served at `/`; API at `/api/` → Spring Boot on :8080. **Follow `DEPLOYMENT.md`**
as the single canonical guide. Routine redeploy is one command:
`powershell -ExecutionPolicy Bypass -File deploy\push-to-aws.ps1 -KeyPath "<your.pem>"`.
`application-prod.yml` defaults to `STORAGE_PROVIDER=S3`; `STORAGE_S3_BUCKET` and the EC2 instance role provide
production object storage. Source migrations are V105; production was last documented at V73 and must be checked in
Flyway logs after the pending local remediation release is explicitly approved and deployed.

### Email through Gmail (admin reports and temporary passwords)

Outbound mail is off in production until `MAIL_MODE=SMTP` is set in `/etc/shifa/shifa.env` (`MOCK` only logs). Set
`MAIL_USERNAME` to the Gmail address, `MAIL_PASSWORD` to its 16-character app password (no spaces, or keep the value
quoted), and `MAIL_FROM` to the same address (Gmail rewrites any other From). `deploy/shifa.env.example` documents each
variable. Then restart `shifa-oms`.
- **Daily and weekly reports** go to every address in `REPORT_DIGEST_TO` (comma-separated) plus every active admin user
  with an email on their profile (`REPORT_DIGEST_INCLUDE_ADMINS=false` sends to `REPORT_DIGEST_TO` only). One message is
  sent per recipient, so one bad address cannot block the rest (`mail/ReportRecipients`).
- **Password reset:** the temporary password is shown in a dialog on the Users page that stays open until closed, and is
  also emailed to the admin who performed the reset (never to the affected user) when mail is not MOCK and that admin has
  an email (`auth/PasswordResetNotifier`). It is sent directly rather than through the outbox, so it is never stored in the
  database, and it is never logged.
- SMTP connect/read/write timeouts default to 10/15/15 seconds (`MAIL_CONNECT_TIMEOUT_MS`, `MAIL_READ_TIMEOUT_MS`,
  `MAIL_WRITE_TIMEOUT_MS`). `POST /api/admin/mail/test?type=digest&to=<address>` (ADMIN) sends a sample to check delivery.

---

## 10. Audit Remediation Plan

The repository-wide security, settlement, deployment, frontend, performance, testing, and product-enhancement findings are tracked one by one in **[`AUDIT-REMEDIATION-PLAN.md`](AUDIT-REMEDIATION-PLAN.md)**. The plan includes priorities, evidence paths, acceptance criteria, validation gates, deferred decisions, and a completion log. Work through P0/P1 items before adding unrelated features.

### Audit remediation A1–A6 completed locally (2026-10-30)

The first security work set is implemented locally and is **not deployed** yet. A1 makes Shopify/courier webhooks fail closed in production and validates required HMAC secrets at startup. A2 adds per-user JWT session-version revocation and server-side forced-password enforcement. A3 replaces the fixed reset password with a generated expiring credential returned only by the reset action. A4 adds V101 AES-GCM MFA ciphertext storage, production key validation, legacy backfill, and MFA failure/enrollment audit events. A5 uses trusted-proxy-aware client IP resolution, overwrites forwarded IP headers in the canonical Nginx config, and separates MFA rate-limit buckets by signed ticket user. A6 removes the credential-bearing demo environment file and adds a redacted demo template. Backend full tests passed **1010/1010**; the admin production build completed with the existing shell CSS budget warning. Before production restart, set `SHOPIFY_WEBHOOK_HMAC_SECRET`, `COURIER_WEBHOOK_HMAC_SECRET`, and `MFA_ENCRYPTION_KEY` in the server environment; do not deploy this batch until those values are confirmed.

### Sliding 30-minute inactivity session and browser-close logout (implemented locally, not deployed)

Authentication now uses a server-authoritative sliding idle timeout of **30 minutes**, not an absolute 30-minute workday limit. Each login/tab receives a hashed server session row and signed `sid`; authenticated API requests touch that row using server time. Silent refresh preserves the same session and refreshes the idle deadline only while the session is still active. Frontend tokens are stored in `sessionStorage`, old persistent localStorage token keys are removed without migration, and successful API responses update the local idle timer. If there is no authenticated activity for 30 minutes, the server rejects the session. Same-tab reload remains authenticated before expiry; closing the browser/tab requires a new login. Migration V102 adds `auth_sessions`. Backend full tests passed **1028/1028**, frontend property tests passed **19/19**, and the admin build completed with the existing shell CSS warning. This batch is not deployed; manually verify active work for several hours, 30-minute inactivity, refresh near 15 minutes, browser close/reopen, and multiple tabs after deployment.

### Settlement hardening B1–B6 completed locally (V103/V104, not deployed)

The Settlement Center now validates settlement target type and ownership, rejects null/invalid targets, locks and conserves allocations, supports safe partial/idempotent resolution, commits V98 imports atomically, distinguishes malformed dates from missing/defaulted dates, normalizes UTRs, records historical UTR duplicates without deleting evidence, and conditionally installs the account-plus-UTR uniqueness boundary. The Angular Settlement Center now provides scoped candidate selection and manual resolution with required reason/amount validation, while CA remains read-only and Admin/Accountant retain mutation controls. V103 adds settlement allocation target keys/reasons/date-default metadata; V104 adds canonical payment UTR storage and duplicate preflight. The older V99 remittance workflow remains separate. B1–B6 are implemented locally and not deployed; final full backend validation and production MySQL preflight must pass before release.

---

## 11. Branches

- `feature/shifa-backend` — prior mainline.
- `dashboard-only` — **current** working branch for the dashboard-only pivot.

### Account-menu and PWA Install visibility fix (2026-09-17)
The top-bar account popover had a contrast regression: the global dark-green navbar rule for `.shifa-navbar .btn-ghost-secondary` also matched the white popover's **My Profile** and **Take the tour** buttons. The final fix uses dedicated `.shifa-usermenu__action` styling with dark readable text, green icons, and visible hover/focus states. The app-bar **Install app** action is now always rendered before Offline, protected from flex shrinking, and opens the native prompt when available or the platform instructions otherwise. The menu/navigation behavior is unchanged.

`npm --prefix frontend run build:admin` completed cleanly with no warnings/errors and produced `main-35QGUIYS.js`. Deployed via `deploy\push-to-new-server.ps1 -SkipBuild` on 2026-09-17. Backup: `~/shifa-backup-2026-09-17-160520.sql`. Live verification: service active, zero restarts, HTTPS root 200, `/api/states` 401, and served bundle `main-35QGUIYS.js`. If a browser still shows the pale old buttons, reload with Ctrl+Shift+R or clear the site's service-worker/cache storage once; the server is serving the new bundle.

### Team Performance 360 — Phases 1–3 implemented & deployed (2026-09-19)
The Team Lead performance page has been expanded beyond the five-card baseline shown in the client screenshots. This is additive and read-only; no migration was added and scope remains server-authoritative (`TEAM_LEAD` sees assigned salespeople only; `ADMIN` sees the whole sales force).
- **Phase 1 operational view:** `/api/team/performance` accepts optional `from`/`to` dates (default current month) and returns selected-period orders, revenue, AOV, today values, previous-period comparison, failed/RTO outcomes, customer outstanding, pending payment amount/count, follow-ups due, and member-scoped target progress. The UI adds period chips/custom dates, action counters (approval/payment/follow-up/failed delivery), member search/health filters, expanded comparison columns, and a richer direct-report drawer with Overview/Activity/Orders tabs.
- **Phase 2 management view:** the page includes revenue/previous-period/target cards, sortable-ready comparison data, explainable health status, CSV management export, and browser Print/PDF. The export is generated from the already scoped response and does not request or expose another team.
- **Phase 3 coaching view:** read-only explainable coaching flags identify inactive members, no activity, follow-up backlog, and RTO/redispatch review priorities. No fake persisted coaching notes or task completion state was introduced; that remains a separate schema decision.
- **Safety:** target totals are filtered by server-resolved member IDs; Team Lead self-punched orders remain excluded from manager performance rollups; direct-report profile privacy and existing 404 authorization behavior are unchanged.
- **Validation + deployment:** backend clean suite **752 tests, 0 failures, 0 errors**; focused `TeamPerformanceServiceTest` 2/2; admin build clean with no warnings/errors, bundle `main-R2TNRM2U.js`. Deployed via `deploy\push-to-new-server.ps1 -SkipBuild` on 2026-09-19. Backup: `~/shifa-backup-2026-09-19-112045.sql` (168K). Live: service active, `NRestarts=0`, Flyway V64/no migration necessary, HTTPS root 200, `/api/states` 401, served bundle `main-R2TNRM2U.js`. If the old five-card page remains visible, clear the Angular service-worker/site cache once and reload.

### Team Lead personal + team performance split (implemented locally, not deployed)
Extended Team Performance so a `TEAM_LEAD` can distinguish their own punched orders from orders punched by assigned teammates:
- Backend `TeamPerformanceResponse` now adds `ownPerformance`, `ownPeriod` and `combinedPeriod`. The existing `leaderboard` and team period remain teammate-only; a Team Lead's own orders are loaded separately through the authenticated user id. (The first version also embedded `ownOrders`/`teamOrders` lists in this response; those were removed in the pagination change below and replaced by a paged endpoint.)
- `TeamOrderRow` carries order code, customer, amount, status, date, and `salespersonName`. Team orders therefore identify the salesperson who punched each order; own-order rows are available in the My orders tab.
- Frontend Team Performance adds **My orders / Team orders** tabs, separate **My performance** KPI cards, and teammate order rows with salesperson names. ADMIN continues to see the global team view without a personal Team Lead tab.
- Existing Orders visibility semantics and server-side `teamMemberScope` are unchanged. This is additive/read-only, with no migration and no client-supplied team/user scope.
- Validation: backend full suite **752 tests, 0 failures**; focused TeamPerformanceServiceTest passed; admin build clean → `main-FCBAQKDI.js`. **Not deployed** yet.

### Reporting compatibility fix — `OrderReportRecord.customerOutstanding` (2026-09-19)
Added the persisted `customerOutstanding` value to the pure reporting projection so Finance Outstanding and Payments reports distinguish customer dues from courier COD remittance. Legacy report-record constructors retain a fallback to `totalAmount - amountReceived`. Updated `DashboardMetricsService.toRecord()` to pass the new final field; this fixes the local Java constructor compilation error at `DashboardMetricsService.java:318` without changing payment or settlement invariants.

### Second `OrderReportRecord` compatibility fix (2026-09-19)
Added a backward-compatible constructor accepting the existing 17-argument shape that includes `LeadSource` while defaulting the newly added nullable `customerOutstanding` projection to `null`. This fixes report/property-test fixtures that pass `List.of()` plus a lead source without the new field; the compact report logic falls back to `totalAmount - amountReceived` for those legacy fixtures. Full backend validation: **752 tests, 0 failures, 0 errors**.

### Multiple payment screenshots per order (V65)
An order could previously carry exactly one payment proof. Salespeople routinely have several — a part
payment plus the balance, a UPI receipt plus a bank confirmation, or two screenshots because the
transaction did not fit one screen — and had to pick one and discard the rest.
- **Capture:** the New Order payment step accepts multiple images and appends on each pick (up to 10 per
  order). Each file uploads independently through the existing `POST /api/orders/payment-screenshots`, so
  one slow or failed upload never blocks the others and a failed proof can be removed and retried on its
  own. The first successful upload is labelled **Primary**.
- **Review:** the order-detail drawer and the Payment Verification viewer both show every proof on file,
  numbered, with the primary marked. A proof whose bytes cannot be loaded is skipped rather than hiding
  the rest, and both surfaces fall back to the original single-proof endpoint if the listing is
  unavailable.
- **API:** `GET /api/orders/{id}/payment-screenshots` lists proof metadata (never the storage key — a
  proof is addressed by id), and `GET /api/orders/{id}/payment-screenshots/{screenshotId}` streams one
  inline. Both keep the existing ACCOUNTANT/ADMIN/PAYMENT_VERIFIER/CA gating; a proof id belonging to a
  different order returns 404. `CreateOrderRequest`/`LeadConvertRequest` gained an optional
  `paymentScreenshotKeys` list; sending only `paymentScreenshotKey` behaves exactly as before.
- **Backward compatible by design:** `orders.payment_screenshot_key` still holds the primary proof, so
  the screenshot-required rule, every `paymentScreenshotAvailable` projection, the invoice/approval
  paths, and the original single-proof endpoint are untouched. Historical orders are backfilled as their
  own first proof.
- **Known gap (deliberate):** abandoned uploads are not garbage-collected. `StorageService` has no
  delete operation on any of its three providers and there is no staging/TTL/sweeper, so adding GC is a
  separate piece of work. The previous single-proof flow already orphaned objects on abandon and on
  replace; multiple proofs make it more likely but do not introduce it.

### Delivered-date ledger posting — COD collection now reaches the general ledger
**Bug fixed.** Automatic ledger posting fired on order approval, purchase orders, expenses, and prepaid
payment *verification* — but nothing fired on delivery. For a COD order the sales voucher debited Sundry
Debtors for the full gross and nothing ever credited it back when the cash was collected, so Sundry
Debtors grew with every delivered COD order while Cash stayed understated. The existing payment source
could not cover it because a pure-COD order never goes through payment verification.
- A new `ORDER_DELIVERY` posting source books the collection as a **Receipt voucher, Dr Cash / Cr Sundry
  Debtors**, dated **the day the order was delivered** — taken from the order's status history (the most
  recent transition into `DELIVERED`, since a redispatched order can be delivered twice), falling back to
  the settlement row and then to the order date. It is a separate source type from `ORDER` because ledger
  idempotency is keyed on `(source_type, source_id)`; reusing `ORDER` would collide with that order's own
  sales voucher.
- Published from both delivery paths in the same transaction as settlement — in-house
  (`ManualDeliveryService`) and courier (`CourierStatusApplier`) — and skipped entirely when the order
  carries no COD amount. As with every other ledger publish, the outbox row commits with the delivery and
  a downstream posting failure can never roll back or alter the order.
- **Revenue- and GST-neutral:** only the cash/debtors side is touched. Revenue recognition and GST output
  stay at invoice/approval date, which is also the correct GST time-of-supply treatment, and were
  deliberately not moved to delivery.
- **Known simplification:** the collection is booked straight to Cash. That is exact for in-house
  delivery; for a courier delivery the courier holds the float, so strictly there is an intermediate "COD
  receivable from courier" leg. The seeded chart of accounts has no ledger for that float and the
  reconciliation module already tracks the courier receivable separately, so that leg is not modelled.
- No migration. Covered by `DeliveryReceiptPostingTest` (delivery-date voucher asserted to differ from
  the order-entry date, redispatch uses the latest delivery, settlement-date fallback, prepaid rejected).

### Bank accounts, UTR capture & Shopify settlement destination (V97)
The Settings page now includes a **Bank Accounts** manager (Tabler bank icon) for active receiving and settlement accounts. Admins can configure UPI/QR, bank-transfer, Shopify-settlement, QuikShip-remittance, cash, and other account purposes; historical accounts are deactivated rather than deleted. The existing singleton invoice bank fields remain for invoice compatibility.

Normal salesperson order entry now captures the payment method, receiving account, and UTR/reference alongside the required upfront payment screenshot. The backend validates active account selection and UTR for UPI/bank payments, snapshots masked account identity on the payment transaction, and exposes scoped payment metadata at `GET /api/orders/{id}/payments`. Payment Verification, Approval Queue, and Order Detail show account/UTR without exposing storage keys. **C6 remains open:** the admin `BankAccountResponse` currently includes both `accountNumber` and `maskedAccountNumber`; do not treat the normal admin list response as privacy-complete until the full value is removed or moved behind a separately audited reveal action.

Shopify settlement tracking is configured separately from the existing Shopify webhook-import switch: Admin Settings can enable/disable settlement tracking and select the default Shopify settlement bank account. Shopify order import remains independent; actual T+1/T+2 payout reconciliation will use the configured destination when settlement batches are added. Migration **V97** adds the bank-account master, payment metadata columns, and Shopify settlement settings.

### Settlement Center operational slice (V98)
The new `/settlements` page adds the first operational reconciliation ledger for bank receipts, Shopify payouts, and QuikShip COD batches. Admin/Accountant can upload CSV/XLS/XLSX statements, preview deterministic matches, and post a batch; CA can inspect batches and lines read-only. Matching uses exact UTR, Shopify order ID, order code, or AWB references, and explicitly reports matched, mismatch, unmatched, ambiguous, duplicate-in-file, and invalid rows. Each batch keeps line-level allocations and suspense amounts, with pagination and a line-review drawer.

V98 deliberately records settlement evidence and allocations without automatically changing existing COD receivables, order statuses, or General Ledger vouchers. The existing QuikShip `/api/recon/remittance/import` flow remains unchanged for its delivery catch-up and COD settlement side effects. Automatic voucher posting and reviewed receivable settlement will be implemented after the allocation rules are approved.

### Dashboard KPI scope fix
The admin dashboard now distinguishes **today’s activity** from **open backlog across all dates**. Orders today, upfront received on today’s orders, COD due on today’s orders, and top salesperson today are day-scoped using the IST business day. Unsettled COD, approvals, payment verification, failed deliveries, and stuck QuikShip shipments are backlog metrics and are labelled accordingly. Payment verification counts exclude terminal REJECTED/PAYMENT_REJECTED/CANCELLED rows, and stuck shipments are restricted to QuikShip orders. This prevents a day with zero new orders from misleadingly showing an all-time ₹10 lakh COD backlog as today’s collection requirement.
### RTO scan fix — scan review modal with comments before marking RTO (implemented locally, not deployed — 2026-10-22)
The `/packing/rto` flow now opens a review modal after a barcode/QR scan resolves. The operator can add optional comments and explicitly click **Mark RTO**; an eligible order is not changed before confirmation. Ineligible orders show their current status without changing anything.

The modal now presents labeled, read-only verification fields for order code, current status, customer name/mobile, order ID, scan source, and courier/AWB details when available. It also has an optional comments textarea limited to 500 characters. The existing guarded RTO endpoint receives `RtoReason.OTHER` plus the entered comments only after the operator verifies the details and clicks **Mark RTO**. The backend resolver accepts bare order codes/AWBs and common partner QR payloads, including URL query/fragment values, the final URL path segment, JSON-like key/value content, and `AWB:`/tracking/order fields. Manual RTO also clears `codAmount` and `customerOutstanding`, matching the courier-driven RTO path while preserving the invoice and payment amounts. No migration was added. Focused backend tests passed: **60/60** (`PackingServiceTest` + `EndpointRoleGuardIntegrationTest`); the admin build generated successfully with the existing shell CSS budget warning. This fix is **not deployed yet**.

### Local HTTPS testing from a phone
The Angular development server is configured for LAN HTTPS on `0.0.0.0:4300` and proxies `/api/**` to the local Spring Boot server at `http://localhost:8080`. This keeps mobile API calls same-origin and avoids mixed-content issues.

Install [mkcert](https://github.com/FiloSottile/mkcert), then from `frontend` generate a certificate containing the laptop LAN IP and local names:
```cmd
mkcert -install
mkdir .certs
mkcert -cert-file ".certs\\shifa-local.pem" -key-file ".certs\\shifa-local-key.pem" 192.168.1.25 localhost 127.0.0.1 ::1
```
Replace `192.168.1.25` with the laptop's IPv4 address from `ipconfig`. Copy the mkcert root CA from `mkcert -CAROOT` to the phone and install/trust it, otherwise the browser may reject the certificate and block camera access. Start the backend normally, then start the frontend:
```cmd
npm --prefix "frontend" run start:admin
```
Open `https://<laptop-ip>:4300` on the phone. Do not use `localhost` on the phone. The `.certs` directory is ignored and must be regenerated on a new machine or after a certificate change.

The local Spring Boot profile now permits the local HTTPS Angular origin (`https://localhost:4300` and the configured LAN origin, default `https://192.168.1.104:4300`) for CORS preflight/login requests. Set `CORS_EXTRA_ORIGIN` when the laptop LAN IP changes, then restart the backend.

### RTO handover eligibility and larger mobile scanner (implemented locally, not deployed — 2026-10-23)
Orders in `HANDED_TO_DELIVERY` are now eligible for the same RTO scan path as `COURIER_ASSIGNED`, `DISPATCHED`, `IN_TRANSIT`, and `OUT_FOR_DELIVERY`. The state machine and transition authority now allow `HANDED_TO_DELIVERY → RTO` for `PACKING_USER`/`ADMIN` (and the existing SYSTEM RTO behavior), so a parcel returned before the asynchronous courier assignment is also closable as Returned/RTO. Independent eligibility and transition tests were updated.

The RTO mobile scan field now uses a full-width 4rem input and 3.5rem camera button. The camera overlay's barcode reticle is taller, giving Code 128/AWB labels a larger target. Focused backend tests passed **24/24**; the admin build completed with the existing shell CSS budget warning. No migration; not deployed.

### Settlement Center upfront-payment account KPIs — implemented locally, not deployed
Clarified settlement scope: UTRs reconcile the partial/full upfront payment captured when an order is placed, not QuikShip COD remittances. Added read-only `GET /api/recon/settlements/kpis` with IST date-window support and an ADMIN/ACCOUNTANT/CA Settlement Center KPI panel. The UI defaults to **Today** and supports Yesterday, This week, This month, All time, and custom From/To dates. It shows one card per bank/UPI account with OMS booked, verified, pending, bank-statement total, UTR-matched, unmatched, and statement-minus-OMS difference. Legacy payments without an account appear under `Unassigned / legacy`. Bank receipt Excel imports now require a destination account, require UTR matching, prevent order-code/AWB fallback for bank statements, and use the statement date as the line date when a row date is absent. The KPI totals combine V97 `payments` metadata with V98 bank-receipt evidence; QuikShip COD and Shopify payouts remain separate. No migration was added; V97/V98 already provide the required data. Backend compile and admin bundle validation passed; not deployed.

### Current audit-remediation continuation (local, not deployed)
- Source is now at Flyway **V105**. V105 adds fenced outbox leases and the indexed due-claim path; integration drainers use bounded claims with lease-token completion fencing.
- C6 bank-account responses now expose masked values only. Blank account-number edits preserve the stored value, so the browser never needs a full account number for ordinary edits.
- F1 remains partially complete: dashboard/report windows use the existing SQL projections/windowed finders and public tracking mobile/detail responses are database-capped, while broad report pagination, all-time policy, and query-count/dataset tests remain follow-up work.
- E1/E2/E3 are implemented in source but retain dedicated route-matrix/date-consistency test gaps. E8 is validated by the shared blob/money/status utilities and frontend property tests. F2 still needs real MySQL contention validation and provider-level idempotency decisions before horizontal deployment.
- No deployment was performed. Production remains documented at V73 until an explicitly approved release applies V101–V105 and is verified from the server journal.

### Local startup fix: authenticated-session schema mapping
The V102 migration intentionally creates `auth_sessions.session_id_hash CHAR(64)`. Hibernate previously inferred `VARCHAR(64)` from `@Column(length=64)`, so MySQL `ddl-auto: validate` failed after V102–V105 applied. `AuthSession.sessionIdHash` now explicitly maps `columnDefinition = "CHAR(64)"`; no migration or data rewrite was needed. Focused auth/session/context tests passed, and a real local MySQL boot validated Flyway V105 plus `Initialized JPA EntityManagerFactory` and Tomcat startup.

### Sales-executive × product reports and top-3 executives (ad targeting and retention) — implemented locally, not deployed
Client request: see what each sales executive sold of every product (quantity, total price, and so on), and see the top 3 executives per category so ad spend and retention effort can back the people who sell each category best. Four new report types were added to the existing Reports page (`/api/reports/{type}` plus the Excel/PDF export); no migration, no change to any existing report contract.

| Report (URL segment) | Rows | Use |
|---|---|---|
| Executive × product detail (`executive-product-detail`) | One per executive and product: category, orders, distinct customers, quantity, total sales, average price, share of that executive's sales % | Which products each executive actually sells |
| Executive product tracker (`executive-product-matrix`) | One per executive: orders, **Gross Sale**, **Net Sale**, total qty, distinct products, then one quantity column per product, plus a `TOTAL` row | Mirrors the client's Excel "Executive Sales Tracker"; best exported to **Excel** (the PDF of a wide grid is cramped) |
| Top 3 executives per category (`top-executives-by-category`) | Up to 3 per category: rank, orders, customers, quantity, total sales, share of category %, repeat customers, repeat rate % | Which executives to back with ads for a category |
| Top 3 executives per product (`top-executives-by-product`) | Same columns, per product | Same, at product level |

Definitions worth knowing:
- Only revenue orders count (rejected, payment-rejected and cancelled are excluded), inside the selected From/To window, same as every other money figure.
- **Gross Sale** is the sum of line totals (GST-inclusive, before the order discount); **Net Sale** is the sum of order totals (after discount and round-off). The gap is the discount given.
- A **repeat customer** bought in at least 2 distinct orders from that executive in that category/product **inside the selected window**. Older history is not visible, so use a wide window (60–90+ days) for retention decisions. Customers are identified by mobile, then by name; an order with neither is never merged into a "repeat".
- The top-3 reports rank only orders punched by an executive. Unattributed orders (for example Shopify) are left out of the ranking so they cannot crowd real people out, and the share % is the share of what executives sold. The detail and grid reports keep those orders under `UNSPECIFIED`, so their totals still reconcile.
- **Categories must be assigned on the Products page** for the by-category report to be useful. The 30 products seeded by V50 have no category, so until they are assigned every product lands in one `Uncategorised` bucket (always listed last). The by-product report works without categories.
- Not covered: the Excel's "No of Calls" column (call counts are not part of the OMS reporting pipeline).
- Visibility: the detail and grid reports sit in the **Salesperson** group; the two top-3 reports sit in a new **Top performers** group that is hidden from salespeople (they only see their own orders, so a top 3 would be misleading). Backend: `reporting/domain/ExecutiveProductAnalytics` (pure), `ReportTableBuilder`, `ReportService` (category lookup through `ProductRepository.findProductCategoryRows()`), `ReportType`.
- Validation: new `ExecutiveProductReportsTest` (16 cases) plus the existing `ReportExportFidelityPropertyTest`, `ApplicationContextLoadsTest` and `EndpointRoleGuardIntegrationTest` pass (90 tests in the focused run); admin build is clean. Not deployed.

### Reports page performance fix (N+1 in the report loader) — implemented locally, not deployed
A month-long report (about 2,300 orders) took over 20 seconds because the report loader ran four queries per order (line items, courier record, COD receivable, claim receivable). `ReportService` now loads all of that in batches of at most 1,000 order ids, so a report costs a handful of statements regardless of order count. This loader backs every report type and the Excel/PDF exports (it also backed the Vyapar export, since removed). Report output is unchanged. `ReportQueryCountTest` guards the statement count and checks that each order still gets its own AWB, COD status and claim status; the full backend suite passes (1065 tests). Restart the backend to pick it up; no migration.

### Descriptive report download filenames — implemented locally, not deployed
Downloaded reports no longer arrive as a generic `report.pdf`. Every report file is named `shifa-<report-name>_<date range>.<ext>`, so admins do not have to rename anything and files sort by date:

| Download | Example name |
|---|---|
| Any Reports-page report (Excel / PDF) | `shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf` |
| Report with no date filter | `shifa-customer-wise-report_all-time.xlsx` |
| Orders list export | `shifa-orders_2026-10-02_to_2026-10-31.xlsx` |
| Salespeople performance (team / one person) | `shifa-salespeople-performance_2026-10-01_to_2026-10-31.xlsx`, `shifa-salesperson-performance-asha-kumari_2026-10-01_to_2026-10-31.pdf` |
| Team performance CSV | `shifa-team-performance_2026-10-01_to_2026-10-31.csv` |
| Partial settlements follow-up CSV | `shifa-partial-settlements-follow-up_as-of-2026-10-31.csv` |
| Remittance batch details CSV | `shifa-remittance-batch-12-details_2026-10-20.csv` |

The date part is `<from>_to_<to>`, a single date, `from-<date>`, `up-to-<date>`, or `all-time`. Names contain only lowercase letters, digits, dashes, underscores and dots, so they are valid on every operating system. GST reports/GSTR-1, invoices, labels and database backups keep their existing names.

Cause of the old behaviour: the server sent `report-<type>.pdf` with no dates, and CORS did not expose the `Content-Disposition` header, so a browser on a different origin (such as the dev server on port 4300 calling port 8080) could not read the server's filename and saved a bare `report`. `Content-Disposition` and `X-Report-Message` are now exposed, and the frontend builds the same descriptive name itself if a header is ever unreadable. The convention lives in `reporting/ReportFilenames` (backend) and `shared/report-filename.util.ts` (frontend); both are tested against the same examples. Backend 1080 tests and frontend property tests (36) pass. Restart the backend and hard-refresh the browser to pick it up; no migration.

### In-house business assistant "Ask Shifa" (implemented locally, not deployed)

Admins can now ask plain-language questions about orders, sales and product quantities on a chat page (**Analytics & Reports → Ask Shifa**, route `/assistant`, ADMIN only). It is rule-based and runs entirely inside the OMS: no AI model, no external service, nothing leaves the server. It cannot answer anything the fixed query catalogue below cannot compute.

- **Endpoint:** `POST /api/admin/chatbot/ask` with `{ "question": "..." }` returns `{ understood, answer, table, suggestions }`. Read-only, no migration.
- **What it understands:** a channel (Portal, Shopify, in-shop/POS, or all sources), a period (today, yesterday, this/last week, this/last month, last N days, this/last financial year, a month name, or a date such as `25 Oct` or `25/10/2026`), a salesperson (typo tolerant; `team <name>` includes a team lead's whole team), a product (typo tolerant: "Gut Cleaner" finds "Gut Cleanser"; part of a name such as "honey" matches every product containing it, and the answer lists them), and a ranking (`top 5 salespeople`, `best selling products`, `sales by channel`, `lowest ...`). Examples: "Orders today from Portal, Shopify and all sources", "Total sales by Sameer Khan this month", "How many units of Gut Cleanser were sold last week?".
- **Numbers match the dashboards:** Portal means neither Shopify nor in-shop (POS). Order counts include cancelled orders; sales and units exclude rejected, payment-rejected and cancelled orders, and the answer says how many were left out. Product sales are line values before any order-level discount. A question with no period covers all time and says so. Orders with no creator (Shopify) appear as "Not attributed" in salesperson rankings and are never ranked as a person.
- **It refuses to guess:** a name that follows "by / for / of" (or a unit question) but matches no salesperson or product gets an "I couldn't find ..." reply with close-name suggestions instead of a company-wide number. A first name shared by two people asks which one.
- **Code:** backend `com.shifa.oms.chatbot` (pure parser in `chatbot/domain`: `QuestionParser`, `PeriodParser`, `NameSuggester`; `ChatbotQueryRepository` JPQL aggregates grouped by order status; `ChatbotService`; `ChatbotController`). Frontend `assistant/` (service, model, chat component). The conversation lives in the browser tab only.
- **Validation:** all source compiles (977 files), the full-context `ApplicationContextLoadsTest` passes (validates the new JPQL and wiring), and the admin production build completes with only the existing shell CSS budget warning. Parser and end-to-end behaviour were verified with temporary checks on in-memory H2 that were then removed; no permanent tests were added because none were requested. Not exercised in a real browser against the real MySQL data yet.
- **Not covered:** customers, stock levels, COD/settlement, GST, and any non-admin role (salespeople would need per-user scoping first).

### "Ask Shifa" extended: orders, bank/QR receipts, COD, stages, attention (implemented locally, not deployed)

The in-house assistant (see the section above; still rule-based, no AI model, nothing leaves the OMS, ADMIN only, no migration) now also answers:

- **Status of an order.** "What is the status of order SHR-20260925-WJS7?" shows the stage, last update, customer, items, amounts, payment check, courier/tracking number and the last few status changes. It also accepts a courier tracking number, a QuikShip order id, or just the last characters of an order number. A customer's latest orders come from their mobile number.
- **Money received per bank / QR account.** "Received in Bank 1 today, Bank 2 or Bank 3" matches the accounts set up under Settings. A number in the question ("Bank 2", "QR 2") matches the account with that number in its name; a name ("HDFC") matches by its distinctive word. A reference that matches no account is reported, with the real account names, and never replaced by "all accounts". Figures use the Settlement Center's "OMS booked" basis (payments by capture time and receiving account) and are split into verified, awaiting verification, rejected and not reviewed. Payments with no account (older orders, cash, Shopify) appear as "Unassigned / legacy".
- **COD to collect.** "Amount to collect on delivery for today, yesterday, this week, this month" uses the dashboard's "COD due" basis: cash on delivery on orders **placed** in the period, cancelled and rejected orders left out. Several periods in one question give one row per period, and received-in-bank and COD can be asked together.
- **Money still to collect** (customers owe, courier owes, loss claims; the Reconciliation page's collectible summary) and **what needs attention** (the owner snapshot: approvals, payment verification, failed deliveries, RTO, COD past the courier SLA, claims, stuck shipments).
- **Orders by stage.** "How many orders are pending approval" counts current state. "Orders delivered today" counts orders whose status **changed to** that stage today (from the order history, each order once). "Orders by status" shows where the orders placed in a period are now. Stage questions cannot yet be combined with a product.
- **Comparison and averages.** "Sales this month vs last month" gives both figures and the % change (noting an unfinished period); "average order value" adds the figure, or a column in tables.
- Typos in "yesterday"/"today" (for example "yerterday") are corrected; everything else the bot cannot place is listed as ignored or refused rather than guessed.

Code: `chatbot/MoneyAnswers`, `chatbot/LookupAnswers`, `chatbot/domain/TopicReader` and `AccountRef`, extended `ChatbotQueryRepository` (receipts, COD, history-based stage queries) and `ChatbotService`. Verified by temporary checks on in-memory H2 (parser 15, end-to-end 3, plus the full-context startup test and the role-guard suite, all passing; the checks were removed because tests were not requested) and a clean admin production build. Not tried in a browser or on the real MySQL data.

### Pagination for Team Performance and other long lists (implemented locally, not deployed)

`/team-performance` had no paging, and its order list was wrong as well as long: the server returned the newest 100 orders of all time, not the selected period, while the screen labelled them "in this period". An audit then found other screens that rendered every row they loaded. No migration.

**Team Performance**
- New endpoint `GET /api/team/performance/orders?scope=team|own&from=&to=&page=&size=` (TEAM_LEAD and ADMIN) returns one page of orders, newest first, filtered to the selected period (default: current month). `scope=team` is the assigned salespeople; `scope=own` is a team lead's own orders (empty for ADMIN). Who is included is always decided from the signed-in user, never from the request. Page size is capped at 100. `to` before `from` returns 400.
- `ownOrders` and `teamOrders` were removed from `GET /api/team/performance` (only this page used them). Everything else in that response is unchanged.
- The My orders / Team orders tabs are server-paged with a rows-per-page selector and loading/error/empty states. The salespeople comparison table and the coaching list are paged in the browser (small lists).

**Other screens now paged in the browser** (shared `admin-pagination`, page size remembered per table): Day Book, Ledger Statement (opening balance row only on the first page, closing row only on the last), Reconciliation → Segregation (prepaid and COD lists), Settlement Center batch lines, Remittance Report (batch lines drawer and the partial-settlement follow-up list), Inventory stock-history modal, and Analytics → delivery performance (courier / state / pincode band; the pager appears only when a table has more than 10 rows). Totals on Day Book and Ledger Statement still cover every row in the period, and the CSV exports still contain every row.

**Still loads everything from the server** (the screen is paged, the response is not): Day Book, Ledger Statement, Reconciliation Segregation, Settlement batch lines, Remittance batch lines and partial list, Inventory stock history (`GET /api/admin/inventory/{id}/movements` is uncapped), and the Analytics delivery slices. This keeps the on-screen totals correct and is fine at today's volumes. Move these to server-side paging if a single period or product grows to many thousands of rows. Also noted, not changed: the settlement batch list is capped at the newest 100 on the server, and `ReconciliationService` loads whole receivable tables before filtering.

**Checked and left alone** (small or already bounded): Team, Teams overview, Leaderboard, dashboard widgets, Settings, Chart of Accounts, Trial Balance, Balance Sheet, Profit & Loss, GST filing, Pick-list.

Validation: backend compiles (983 main and 228 test sources). `TeamPerformanceServiceTest` (2), `ApplicationContextLoadsTest` (1) and `EndpointRoleGuardIntegrationTest` (46) pass. A temporary in-memory H2 check of the new order page passed and was then deleted: it covered the period filter, team vs own scope, newest-first order, the salesperson name on each row, ADMIN behaviour, an empty team, and an end date before the start date. The admin production build completes with only the existing shell CSS budget warning (bundle `main-3OPS7FGJ.js`). The screens were not exercised in a browser, and the other lists were not tested with large datasets.

### Codebase health pass: security and reliability fixes (implemented locally, not deployed, 2026-10-31)

A review of the whole codebase turned up the issues below. Each was checked against the code before being changed. No migration (V105 is still the highest).

**Security**
- **Global search leaked other salespeople's orders.** `GET /api/admin/search` called the unscoped order finder, so any salesperson, team lead or packer could type a name or mobile fragment and see other people's order codes, customer names, statuses and totals. It now uses the same scope as the Orders page (`SalespersonScopeResolver.creatorScope`): a salesperson finds only their own orders, a team lead their own and their team's, and accountant/CA/admin/packing see all. Scoped roles no longer receive registered-customer-account hits.
- **Open self-registration removed.** `POST /api/auth/register` let anyone on the internet create an account and receive a signed-in session that passes every endpoint requiring only a login. Nothing used it (the storefront is retired). The endpoint, `AuthService.register`, `RegisterRequest` and the welcome-email dependencies are gone; accounts are created by an administrator.
- **Anonymous invoice download removed.** `GET /api/track/{orderCode}/invoice` returned the full invoice PDF (customer name, address, mobile, seller GSTIN) to anyone who knew an order code, and each call allocated the next GST invoice number, including for pending or rejected orders. Nothing used it and the service method is deleted. Staff still download invoices through the authenticated `GET /api/orders/{id}/invoice`. Public status tracking (`/api/track/{orderCode}`, `/api/track/t/{token}`, `/api/track/by-mobile`) is unchanged.
- **Activity log now checks ownership.** `POST /api/activities/log` trusted a client-supplied lead id or customer mobile. It now authorises the lead (or the customer) with the same scoping as the read side, and records against the lead's own mobile, so a salesperson cannot write into another person's timeline. The Angular app does not call this endpoint today.
- **Webhook size cap in Nginx.** `deploy/nginx-shifa.conf` gains a `/api/webhooks/` location limited to 1 MB. The handlers buffer the raw body to compute the HMAC, and the global 15 MB upload limit let an anonymous caller pin that much heap per request. Apply with the next deploy: copy the file to the server and `nginx -t && systemctl reload nginx`.

**Reliability**
- **Low-stock alerts never fired.** `LowStockAlertJob.sweep()` was `@Transactional(readOnly = true)` but inserts an outbox row per product. MySQL rejects writes in a read-only transaction and the per-product catch logged and swallowed the error. It is now a normal transaction.
- **Backup timeout could never trigger.** `ProcessBuilderDatabaseDumpRunner` read mysqldump's stdout to the end before waiting with a timeout, and read stderr only afterwards (a possible deadlock). Both pipes are now drained on their own threads and the 300 s timeout applies to the process itself.
- **One scheduler thread for every job.** Spring's default pool is a single thread, so a slow backup or courier poll stalled the WhatsApp, email, courier, QuikShipX and ledger drainers. `spring.task.scheduling.pool.size` is now `${SCHEDULER_POOL_SIZE:4}`. A job still never overlaps itself.
- **SSE relay loaded every pending outbox row.** `OutboxSseRelay` read all `PENDING` rows of every type every 5 s and skipped most in memory, which grew without bound while an integration was backed up. It now asks the database for just its event types, one bounded batch (`findByStatusAndEventTypeInOrderByCreatedAtAsc`).

**Admin app**
- Command palette "Approval queue" pointed at a route that does not exist (`/approvals`); it is now `/approval-queue`. Product search hits only appear for roles that may open Products (ADMIN, SALESPERSON), instead of sending everyone else to the forbidden page.
- A catch-all route now sends unknown URLs home (the shell guard then routes a signed-out visitor to login) instead of failing with NG04002 and a blank view.
- The Orders Export/CSV buttons are shown only to roles the export endpoint allows (a payment verifier saw buttons that always returned 403).
- New Order: a draft write queued by the 800 ms autosave debounce could fire after a successful save and bring back "You have an unfinished order" for an order that already exists.
- Settlement Center: opening batch B while batch A's lines were still loading could show A's lines under B's header; late replies are now ignored and the pager uses the same clamped page as the table.
- Team Performance: the KPI request now has the same stale-response guard as the order list, so quick clicks on Today / This month cannot leave one period's figures under the other's label.
- Guided tour window listeners are removed when the shell is destroyed; the page title on routes that are not menu links (for example `/orders/12/edit`) now uses the parent section instead of "Dashboard"; the Ask Shifa table no longer throws on an empty first cell and keeps at most 100 messages; two display helpers (`users` created date, `gst-filing` snapshot time) now pin Asia/Kolkata.

**Validation:** backend `clean test` 1075 tests, 0 failures (was 1080; the difference is the five tests of the two removed endpoints); `ApplicationContextLoadsTest` boots the full context; frontend property tests 7 suites / 36 tests; admin production build completes with only the existing shell CSS budget warning. The global-search scoping was checked with temporary unit tests (salesperson, team lead, team lead with no team, accountant) that passed and were then removed because tests were not requested. Not exercised in a browser.

**Open items found but deliberately not changed**
- **Packers and the Orders page.** The packer's menu, bottom tab, packing-queue rows and RTO scan all link to `/orders`, but the backend has never let `PACKING_USER` list or open orders, so those links end in a "forbidden" error. Fixing it either way is a product decision: grant packers read access to orders (this exposes all customer details to warehouse staff) or remove the links.
- `GET /api/track/{orderCode}` (status, tracking number, courier link) is still public by guessable order code and is not rate-limited; `/api/track/t/{token}` is the unguessable replacement and is what the Angular tracking pages use.
- Migration `V103` backfills `settlement_allocations.target_key` with `COALESCE(payment_id, order_id, receivable_id)`, which can differ from the application's `canonicalKey` for COD receivable rows. It only matters for a database that already holds V98 allocations; the production path (V98 to V105 applied together onto empty tables) is unaffected. Do not edit V103 if it has been applied anywhere; add a corrective migration (V107 or later, since V106 is the query-index migration) instead.
- V104's unique index on `(bank_account_id, utr_normalized)` is wider than the application's duplicate check, which excludes the same order. If an order could hold two payment rows with the same account and UTR, the insert would fail instead of passing the check; not confirmed to occur.
- The live SSE feed passes the access token in the URL query (browsers cannot set headers on `EventSource`), so it can appear in Nginx access logs. Exclude the query string for `/api/admin/events` from the log format, or move to a short-lived stream ticket.
- Several date builders in `expenses`, `profit-loss`, `analytics`, the CA GST pages and the Orders quick-select use the device's local time zone rather than IST. They are correct on an IST device; use `todayIst()` / `monthStartIst()` from `shared/business-date.util.ts` if staff ever work from another time zone.
- Offline order creation is disabled, but an older queued order left in a browser can block the queue because there is no discard button.

### Backlog pass: performance, accessibility, shared helpers, lazy loading (implemented locally, not deployed, 2026-11-01)

Follow-up to the health pass above: the refactor, UX and enhancement review was worked through. Behaviour is unchanged unless stated. **Highest migration is now V106.**

**Backend performance**
- **Order search is capped.** `GET /api/orders` (`OrderService.search`) used to load every order for an unscoped caller and filter in memory. It now runs one native query, `OrderRepository.searchRecent`, with a hard limit of 200 newest matches (`SEARCH_RESULT_LIMIT`) for every scope shape (everyone, one salesperson, a team). A scope that is present but empty still returns nothing.
- **My Day, win-back, reorder-due and leaderboard are database aggregates** (`salesperson/MyDayQueryRepository`, JPQL). They no longer load a salesperson's, or the whole business's, full order history on every dashboard open. Win-back groups by the raw mobile column, so a number stored with stray spaces would count as a different customer (mobiles are validated to 10 digits at entry).
- **Admin team overview and channel dashboards are aggregates too** (`dashboard/TeamsOverviewQueryRepository`, `dashboard/ChannelAggregateRepository`; `TeamsOverviewService`, `ChannelDashboardService` and `order/ChannelSummaryService` take them in their constructors). The team overview still calls `SalespersonPerformanceService.leaderboard()` once, which aggregates the whole orders table; moving that to SQL is a follow-up.
- **GSTR-1 no longer runs one query per order.** `Gstr1ReturnService` loads orders with their line items in one fetch join and loads credit-note originals with `findByIdInWithLineItems`. `spring.jpa.properties.hibernate.default_batch_fetch_size` is 100, which batches the remaining lazy collections (pick-list, payment queue) everywhere.
- **V106 adds five indexes:** `orders(created_by, created_at)`, `orders(customer_mobile, created_at)`, `orders(payment_verification_status, created_at)`, `payments(captured_at, bank_account_id)`, `status_history(to_status, changed_at)`. Do not edit V106 after it has been applied anywhere.
- `ReturnService` now logs (at error level) when the refund ledger post cannot be queued; the refund itself still succeeds.

**Backend structure**
- `AdminOrderController`: the duplicated creator-scope and `source` parsing in `list` and `export` is one helper each. **The export is now strict:** an unknown `source` value returns 400 instead of silently exporting every source. The list stays lenient for stale clients.
- `OrderService`: the audit diff moved to `OrderEditDiff` and the payment-proof key and hash helpers to `OrderPaymentProofs` (both package-private, pure). Fully qualified class names were replaced with imports (also in `TrackingService`). All five constructors are kept: four test classes subclass the service.
- `dashboard/OrderStatusCounts` is the single `GROUP BY order_status` fold used by `RoleDashboardService`, `DashboardMetricsService` and `OwnerSnapshotService`. The unused `OrderRepository.findAllOrderSources` was removed.
- About 45 files had storefront-era comments and Javadoc corrected (comments only). `OrderSource.STOREFRONT` is marked legacy. `MailProperties` and `EmailBrandProperties` keep their different default site URLs; both are documented, neither changed.

**Admin app: bundle and structure**
- **Every page is a lazy route** (`app.routes.ts` uses `loadComponent`; only `AdminShellComponent` and the guards are eager). The initial bundle fell from 3.41 MB to 1.29 MB raw (about 219 kB transferred). The service worker still pre-caches all chunks (`/*.js` in `ngsw-config.json`, deliberately unchanged), so pages keep working offline. The `qrcode` library loads only when a user enrols in MFA. The `angular.json` initial budget is now 1.75 MB warning / 2.5 MB error (was 3.5 / 4).
- The shell is split: `shell/tour-steps.ts`, `shell/nav.model.ts`, `shell/nav.config.ts` (`ALL_NAV`, `BOTTOM_TABS_BY_ROLE`). `admin-shell.component.ts` went from about 1,090 to about 625 lines.
- Orders: pure rules moved to `orders/order-permissions.ts`, `orders/order-presentation.ts` and `orders/order-totals.ts`; the components keep thin methods with the same names for their templates. `new-order` and `edit-order` now share one copy of the money maths.
- Shared helpers: `shared/tracking-url.util.ts`, `shared/date-label.util.ts`, `shared/enum-label.util.ts`; `formatInr` is sign-first (`-₹1,234.00`, never `-₹0`) and has `formatInrOrDash` / `formatInrSigned`. About 25 local rupee formatters now delegate to it; hand-rolled blob downloads use `saveBlob` / `openBlob`; `expenses` uses IST for "today"; order-status labels come from `humanizeStatus`.

**Admin app: accessibility and states**
- **`shared/dialog.directive.ts` (`adminDialog`)** gives every drawer and modal `role="dialog"`, `aria-modal`, focus on open and restore on close, a Tab trap, body scroll lock and Escape via `(dialogClose)`. It is applied to all dialogs in orders, customers, approval, leads, shell, settlements, reconciliation, products, inventory, expenses, users, returns, purchase orders, suppliers, meetings, tasks, payments, packing, salespeople, CA GST, teams overview, remittance report and WhatsApp templates. Use it for any new dialog, rendered inside an `@if`. `ConfirmDialog` focuses Cancel for destructive confirmations.
- Clickable rows are keyboard operable (Tab, Enter, Space); the "clear search" controls are real buttons; the lead form labels are linked to their inputs; the global search and the product and state typeaheads expose combobox/listbox semantics; the account menu uses the disclosure pattern; pagination and toast buttons reach 44 px on touch screens.
- Error states now have a Retry: Profit & Loss, Reports, Settlement Center (also when batches are already loaded), the Reconciliation lists and widgets, and the order, customer and lead drawers. Approval Queue uses the shared toast service. Two subscribes that had no error handler now show a toast.

**Behaviour you may notice**
- On the Packing scan screen the "confirm next move" dialog opens with Cancel focused, so a scanner's trailing Enter can no longer confirm a move; scanning another parcel while the dialog is open closes it.
- Pressing Escape inside a dialog that has a row-action menu open closes the dialog (the menu listens on the document, after the dialog).
- The Team Member drawer and the profile pages (My Profile, Profile approvals, Salespeople) format their dates in IST on every device, through the shared `formatDateLabel`.

**Validation:** see the matching section of `.kiro/steering/project-memory.md` for the final test counts. Temporary H2 checks of `searchRecent`, My Day, win-back, reorder-due, the leaderboard, the team overview and the channel summary and dashboard passed with hand-computed figures and were then deleted. Not exercised in a browser.

**Deferred on purpose:** the order-detail drawer and items-editor component splits; moving about 34 hand-rolled pagers onto one helper (no end-to-end runner to catch regressions); `ngsw-config.json` changes; the packer access decision to Orders; SQL rewrites for `SalespersonPerformanceService.leaderboard`, `RetentionService`, `ProfitLossService` and `ReconciliationService`; the shell component CSS budget warning (13.1 kB against a 12 kB limit).

### Vyapar export removed from Reports (implemented locally, not deployed, 2026-11-01)

The Reports page no longer offers **Vyapar CSV** or **Vyapar Excel**, and the backend that served them is gone. No migration.
- **Backend:** `GET /api/reports/vyapar`, `ReportService.vyaparTable`, `VyaparTableBuilder` and `TabularData.headerOnly` are deleted. `ReportController` no longer takes a `CsvReportExporter` (the Orders list export still uses it), and the `X-Report-Message` response header and its CORS exposure were only for the Vyapar "no orders found" notice, so they are removed too. `VyaparEmptyRangeExportTest` is deleted; `ReportExportFidelityPropertyTest` keeps the Excel, PDF and CSV round-trip checks and drops the Vyapar property, and `ReportDownloadNamingTest` drops the Vyapar naming case.
- **Admin app:** the two buttons, `exportVyapar`, `ReportsService.vyapar`, the `VyaparFormat` type and the `message` field of `DownloadResult` are removed. The export card now shows Excel and PDF side by side.
- Client-facing material under `docs/` (pricing and presentation pages, the features guide) still mentions Vyapar exports; update it before it is sent to the client again.

### GST pages moved under Finance in the menu (implemented locally, not deployed, 2026-11-01)

**GST & Accounting**, **GST Filing** and **GST Reconciliation** now sit in the **Finance** menu group (after Settlement Center) instead of **Analytics & Reports**. Only the menu changed: the routes (`/ca/gst`, `/ca/gst/filing`, `/ca/gst/reconciliation`), the ADMIN + CA access rules and the CA bottom tabs are as before. The page eyebrow above the title and the desktop sidebar's auto-expanded group follow the new location. The change is in `frontend/.../shell/nav.config.ts` (`ALL_NAV`). Frontend only, no migration.

### Growth analytics on the Analytics page (implemented locally, not deployed, 2026-11-01)

The `/analytics` page was four tabs (Targets, Retention, Forecast, Delivery). It now has ten, and the four original tabs are unchanged. Everything is ADMIN only, read-only, and needs **no migration** (V106 is still the highest).

**Tabs (in order):** Overview, Customers, Retention, Products, Acquisition, Operations, Delivery, Forecast, Targets & Incentives, Data. The open tab is kept in the address as `?tab=<key>`, so a link such as `/analytics?tab=customers` opens that tab, and no parameter opens the Overview. The tab row scrolls sideways on a phone.

**Period.** Overview, Products, Acquisition, Operations and Data share one period bar: last 7, 30 or 90 days, this month, last month, this financial year (from 1 April), last 12 months, or custom dates. The default is the last 30 days. Dates are IST calendar days. The longest period is 731 days (366 for data downloads); the page warns instead of letting a download fail. Customers, Retention, Delivery, Forecast and Targets do not use the period (Customers and Retention are all time).

| Tab | What it shows |
| --- | --- |
| Overview | Orders, sales, average order value, customers, new vs returning, discount, lost orders, each against the previous period of the same length. Sales trend (daily, or weekly above 92 days), channel, lead-source and payment mix, top states and cities, and an orders-by-weekday-and-hour heat map. Above these, up to 12 **key pointers** ranked warning, opportunity, positive, info. Each says what happened, what to do, and links to the tab that explains it. |
| Customers | Every customer is in exactly one segment, each with a suggested action and a ready call list (tap to call, send a WhatsApp follow-up, or copy the listed mobile numbers). Value concentration (top 10% / top 20% of customers by revenue), order-frequency and recency bands, top customers, and a link to the full customer file under Data. |
| Products | Units, revenue, buyers and repeat-buyer share per product, change against the previous period, ABC class (Pareto chart), and the products bought together most often (with lift). |
| Acquisition | Revenue, orders, average order value and delivery success by lead source, plus the lead funnel (conversion, lost reasons, pipeline) from the existing `/api/leads/reports/*` endpoints. |
| Operations | Median, 90th percentile and average hours for order placed to approved, approved to handed over, handed over to delivered, and order placed to delivered (from the order status history); orders by stage; delivery success by payment type; rejection and RTO reasons. |
| Data | Four CSV downloads with their columns documented on screen. See below. |

**Definitions (same as the rest of the app).**
- Revenue and order counts exclude REJECTED, PAYMENT_REJECTED and CANCELLED. Those are counted separately as lost orders.
- Delivered is DELIVERED, COD_COLLECTED or CLOSED. Failed is CUSTOMER_REJECTED, DELIVERY_FAILED, RTO or REDISPATCH.
- Customers are keyed by 10-digit mobile number. The Shopify placeholder `0000000000` is left out of customer analytics.
- **Segments.** One order: New (last order within 30 days), Needs attention (31 to 90 days), One-time lapsed (over 90 days). Two or more orders: Champion (4 or more orders and ordered within 60 days), Loyal (within 60 days), At risk (61 to 180 days), Hibernating (over 180 days). The "at risk" figures on the Overview and in the pointers count At risk plus Hibernating.
- **ABC class.** A while the products before it make up less than 80% of revenue, B up to 95%, C after that.
- Product pairs: the 10 most common pairs that appear together in at least 2 orders. Orders with more than 12 different products are skipped when pairing. Lift above 1 means the pair is bought together more often than chance.

**Data downloads (for data engineers).** `GET /api/admin/analytics/datasets` lists the datasets and columns; `GET /api/admin/analytics/datasets/{key}/export?from=&to=` returns the CSV (named like `shifa-data-orders_2026-10-03_to_2026-11-01.csv`).
- `orders` (one row per order, 27 columns), `order_items` (one row per line, 11), `daily_sales` (one row per day, 6), and `customers` (one row per customer, 10, all time).
- Orders, items and daily sales carry a one-way **`customer_key`** instead of the mobile number: the first 8 bytes of SHA-256 of `salt:mobile`, as hex. The same key appears in every file, so the files join. Only the `customers` file has name and mobile, because it is the call list. Treat it as personal data.
- Limits: 100,000 rows per file and 366 days (default 90). Larger requests get a plain-language error. Every download is audited as `ANALYTICS_EXPORTED`.
- Excel is not offered on purpose: the shared Excel exporter writes every cell as text, which is the wrong format for numbers that will be loaded into other tools.
- **Set `ANALYTICS_CUSTOMER_KEY_SALT`** in `/etc/shifa/shifa.env` for production (property `app.analytics.customer-key-salt`; example in `deploy/shifa.env.example`) and keep it fixed. If it is left at the default, the keys can be reproduced by anyone who knows the default and has a mobile number. Changing it later changes every key.

**Backend.** `analytics/domain` holds pure engines over narrow fact rows: `OverviewEngine`, `CustomerSegmenter`, `ProductEngine`, `FulfilmentEngine`, `PointerEngine` (about 15 rules), `AnalyticsMath`, `Facts`. `AnalyticsQueryRepository` (JPQL on `OrderEntity`, so deleted orders stay hidden) loads the orders, lines, customers and status stamps; `GrowthAnalyticsService` assembles the reports; `AnalyticsDatasetService` builds the CSV files; `AnalyticsController` serves them. The loader methods of `GrowthAnalyticsService` are `public` on purpose (the bean is proxied for transactions). Audit constants `ANALYTICS_EXPORTED` and `ENTITY_ANALYTICS` were added.

**Frontend.** `analytics/analytics.component.*` (tabs, period bar, `?tab=` handling, per-tab loading and error states with retry; a late reply for an old period is dropped), `analytics-period.util.ts` (IST presets and validation), `analytics-format.util.ts`, six components under `analytics/tabs/`, and the new models and service calls in `analytics.model.ts` / `analytics.service.ts`.

**Checked.** All reports and all four dataset files ran against the real local MySQL (2,231 orders in the last 30 days, 2,013 customers), including the 731-day maximum span. Every field the backend sends exists in the TypeScript models. These checks used temporary tests and scripts that were deleted afterwards. Hand-computed figures (KPIs, segments, ABC classes, pairs, speed statistics, dataset row and column counts) were checked on H2 the same way. See `.kiro/steering/project-memory.md` for the final test counts and bundle hash.

**Not done / notes.**
- No permanent tests were added for the new engines (none were requested).
- The screens were not opened in a browser. Check the charts (trend with two axes, Pareto, stacked bar) and the phone layout of the tab row and period bar after a hard refresh.
- The local data only spans about three months, so At risk and Hibernating are empty here; they will fill as the data ages.
- "Calls made" from the sales Excel is not included (no call data in the OMS).
- Nothing is deployed. Production needs the salt set before the first restart that includes this change.

### Production deploy safety tooling and the Nov 2026 release report (2026-11-01)

Preparing the V82 → V106 production release added read-only checks and hardened the deploy script. **Nothing was deployed.**

- **`deploy/prod-check.ps1`** (`-Mode Preflight | Baseline | Verify`) uploads and runs **`deploy/prod-check.sh`** over SSH. Every SQL statement is a `SELECT`, secrets are reported only as "set (N chars)", and output is saved under `deploy/reports/` (git-ignored). Preflight checks service, disk, Java, required secrets, Flyway state, backup path and what the release will do to live data. Baseline snapshots order, line-item and payment totals; Verify re-checks health and Flyway and compares those totals before and after.
- **`deploy/aws-apply.sh`** now runs guards before touching the box (fat JAR size, JAR migrations not older than the database, no failed Flyway rows, `JWT_SECRET`, `MFA_ENCRYPTION_KEY`, Shopify and courier webhook secrets present), verifies the dump finished, keeps the previous JAR and admin bundle, and waits on `/actuator/health`. If the new version does not come up and **no migration was applied**, it restores the previous JAR and bundle automatically. If migrations were applied the old JAR cannot boot on the new schema, so it stops and prints the fix-forward and restore options. `demo-apply.sh` does not have these guards yet.
- **Findings on production (2026-11-01):** database at V82, 2,860 orders; `MFA_ENCRYPTION_KEY` and `COURIER_WEBHOOK_HMAC_SECRET` were **missing** (the new JAR would abort at startup after Flyway ran) and `ANALYTICS_CUSTOMER_KEY_SALT` was unset. The live JAR is dated 26 Oct and already contains the RTO Scan Fix (`315f3b4`) and Sales Person Order Fix (`1998750`) from the `Shifa-Reports-Support-17Oct` branch.
- **Docs:** `docs/DEPLOY-RUNBOOK.md` (internal step-by-step, side effects, failure handling), `docs/Release-Report-2026-11-01.html` (client-facing change report, updated 2 Nov: 18 updates since baseline commit `989ba1f`, 788 files, 27 database additions V83–V109, status live) and `docs/Release-Executive-Summary-2026-11-02.pdf` (two-page plain-language "What's new in Shifa" with clickable links to each screen, including the public customer tracking page `/track-order`; the `.html` next to it is the source and is rendered with headless Edge).
- Verified on HEAD `6feaa30`: `mvn clean test` 1071 tests, 0 failures; admin build bundle `main-6YJ2T4NW.js`; fat JAR 103.2 MB with migrations up to V106.
- Use `deploy/deploy-both.ps1 -ProdOnly`. `deploy/push-to-aws.ps1` still defaults to the demo IP.

### UTR / transaction reference is optional at order entry (2026-11-01)

Salespeople were blocked from punching UPI or bank-transfer orders without a UTR. The UTR is now **optional**; the **receiving account is still required** for UPI/QR and bank transfer (Cash needs neither). Changed `OrderService.resolvePaymentCapture` (server rule removed) and the New Order form (`paymentMetadataInvalid()` now checks only the account; the field is labelled optional). A blank UTR is stored as `NULL`, so the same-account duplicate check and the V104 unique index (both ignore NULL) cannot reject it; a UTR that *is* entered is still checked for reuse. Side effects to expect: orders without a UTR cannot be auto-matched by UTR in the Settlement Center (they fall to manual or amount matching), and the payment-verification screens show no UTR for them. No migration. `OrderServiceTest` (46), `ApplicationContextLoadsTest` and `EndpointRoleGuardIntegrationTest` pass. Not deployed.

### Payment methods are now managed in Settings (V107, implemented locally, not deployed)

The **Payment method** list on New Order (previously a fixed UPI / QR, Bank transfer, Cash) now comes from the database, so an admin can add, rename, reorder, deactivate or delete methods and pick the default without a release.

- **Where to manage it:** Settings → Bank tab → **Payment methods** (above Receiving accounts). Admin only. Each method has a name, a receiving-account rule, a sort order, Active, and Default. The default is pre-selected on New Order; there is always exactly one active default, so it cannot be unset, deactivated or deleted until another method is made the default.
- **Receiving-account rule** (one dropdown): *Salesperson chooses the account*, *No account needed* (cash), or *Auto-select the \<purpose\> account* (for example Shopify settlement).
- **Credit Card Link** is seeded by V107 and is set to *auto-select the Shopify settlement account*. When a salesperson picks it, **Amount received in** is filled with the Shopify account and lists only accounts of that purpose. The account used is the *Default Shopify settlement account* from Settings if one is set and active, otherwise the default-flagged, then first, active account of type Shopify settlement. **An admin must add an account with purpose "Shopify settlement" (Settings → Receiving accounts) before this method can be used;** until then New Order shows a "no account is set up" warning and the server rejects the order with a matching message.
- **Delete vs deactivate:** a method keeps its code as plain text on each payment (`payments.payment_method`, no foreign key), so one that already has payments cannot be deleted and shows a *Deactivate* button instead; an unused one can be deleted. The table shows how many payments each method has.
- **Data model:** `payment_methods` (`code` immutable and generated from the first name, `label`, `requires_account`, `linked_account_type`, `active`, `default_method`, `sort_order`). The hard-coded `order.PaymentMethod` enum is removed; `OrderPayment`, the order-payment, approval-queue and payment-queue DTOs now carry the code as a string. Existing payments (`UPI_QR`, `BANK_TRANSFER`, `CASH`) keep working because V107 seeds those same codes.
- **API:** `GET /api/payment-methods` (ADMIN, SALESPERSON, TEAM_LEAD; active methods with the resolved `autoAccountId`) and ADMIN `GET/POST /api/admin/settings/payment-methods`, `PUT /{id}`, `POST /{id}/default`, `DELETE /{id}`. `OrderService` validates the method on create, edit and resubmit: blank means the default, unknown or inactive is rejected, and a linked method must use an account of that purpose (the server fills it in if the client sent none).
- **Not changed:** the order drawer still prints the raw method code beside each payment (it already did for `UPI_QR`); showing the label there would need the label on the payment DTOs.

### Daily Sales & Incentives: tracker, month calendar, product report and bonus rules (V108, implemented locally, not deployed)

The client pays a flat daily bonus when a salesperson's sales for the day reach a slab (for example ₹25,000 earns ₹500, ₹35,000 earns ₹750). This adds the rules, a day-by-day tracker, a month calendar per person and a product-by-day report. All of it is ADMIN only.

- **Where:** the rules are in **Settings → Incentives** (outside the main settings form, with their own Save). The tracker is **Analytics & Reports → Daily Sales & Incentives** (`/incentives`) with three views: *Daily tracker* (every salesperson by day, bonus shown on each day), *Month calendar* (one person, with the highest and lowest days marked) and *Products by day* (date, salesperson, product, quantity, sales). Each view downloads as Excel or PDF with the usual `shifa-<report>_<dates>` file name. Tapping a day opens its products and the bonus slab it reached.
- **The rules and their history (the audit trail).** Saving never edits old rules. Each save adds a new immutable version with the start day, who saved it, when, an optional note, and a plain-language summary of what changed ("Changed the ₹35,000 slab bonus from ₹700 to ₹750; added the ₹50,000 slab"). The same sentence is written to the Audit page as `INCENTIVE_RULES_UPDATED`. Because each past day is judged by the version in force on that day, changing the rules never rewrites bonuses already earned, unless an admin deliberately picks a start day in the past (the screen warns and asks to confirm). Saving with no slabs switches the bonus off from that day. Nothing is seeded: the admin enters the slabs and the programme start day (the editor suggests 25,000 → 500 and 35,000 → 750 on first use).
- **How the bonus is worked out.** A day earns the **highest slab it reaches**; slabs do not add up (₹40,000 earns ₹750, not ₹500 + ₹750). A day that exactly meets a slab qualifies ("at least"). Sundays earn a bonus like any other day.
- **What counts as sales.** The net order total (after discount and rupee round-off) on the Asia/Kolkata day the order was punched, credited to whoever created it, so an admin placing an order on behalf of a salesperson credits that salesperson. Rejected, payment-rejected and cancelled orders are excluded, as on the dashboards. Shopify imports and orders created by an admin or team lead for themselves belong to no salesperson and are not shown. Only users with the Salesperson role are listed (active ones always; inactive ones only if they sold in the month).
- **Highest and lowest day.** Looked at over days that are **not Sundays**, are not after today, and **had at least one order**. A day with no sales is not a "lowest sales day": Monday–Saturday days up to today with no order are counted separately as *no-sale days*. When days tie, all are marked. Sunday sales still count in the totals and the bonus.
- **Figures are live.** If an order is cancelled or rejected later, that day's sales and bonus drop. Download at month end to keep a fixed copy. There is no "paid" marker yet.
- **Product report.** Product sales are the order lines' values before any order-level discount, so a day's lines can differ slightly from its net sales; the difference is shown as "order discounts and rupee round-off". Periods are limited to 366 days.
- **API (all ADMIN):** `GET /api/admin/incentives/tracker?month=yyyy-MM`, `GET /api/admin/incentives/products?from&to&personId`, `GET /api/admin/incentives/export?report=tracker|calendar|products&format=xlsx|pdf&...`, `GET/POST /api/admin/settings/incentive-rules`. Downloads are audited (`INCENTIVE_REPORT_EXPORTED`).
- **Data:** `incentive_rule_sets` (effective date, note, change summary, who/when) and `incentive_tiers` (minimum daily sales, bonus; cascade-deleted with their version). Code: `com.shifa.oms.incentive` (pure `domain/` engine: `RuleTimeline`, `IncentiveEngine`, `IncentiveRuleDiff`), frontend `incentives/` and `settings/incentive-rules-manager.component.*`.
- **Not included (decisions for the client):** team leads and admins are not part of the daily incentive; no mark-as-paid or payout approval; no per-salesperson rule overrides; rules are company-wide.

### Shopify payments are booked under the Shopify settlement account automatically (no migration, deployed to production 2026-11-01)

Shopify collects the money a customer pays on its checkout and pays it out to the business later, so that money belongs under the **Shopify settlement** account, not "unassigned". Until now an imported Shopify payment was saved with no payment method and no receiving account, so the Settlement Center counted it under *Unassigned / legacy*.

- **New Shopify orders.** When the `orders/create` webhook imports an order, the amount already paid on Shopify (the full total for a paid order, or the paid part of a part-paid one) is saved as a payment with the Shopify settlement account (id plus name and masked-number snapshots) and the payment method linked to that purpose (**Credit Card Link** by default). A pure pay-on-delivery Shopify order has no payment row, so nothing is booked; the balance is collected by the courier, not by Shopify. No UTR or screenshot is set.
- **Which account.** The same rule as Credit Card Link at order entry (`PaymentMethodService.linkedTarget`): the *Default Shopify settlement account* chosen in Settings if it is active, otherwise the default-flagged active account with purpose "Shopify settlement", otherwise the first active one. Deactivated accounts are ignored. If the Credit Card Link method was deleted, the account is still stamped and the method code is left empty.
- **If no such account exists** the order is still imported (a real customer order is never blocked) and the payment is saved without an account; a warning is logged. **Production has no Shopify settlement account yet:** add one under Settings → Bank (purpose "Shopify settlement"), then use the card below.
- **Existing Shopify payments.** The **Shopify Sync** page has a new *Settlement account* card showing the account in use and how many earlier Shopify payments (and how much money) have no account. *Assign N to \<account\>* books them. It only touches Shopify-order payments whose account is empty, never overwrites an account someone already chose, fills the method code only if it is empty, and is safe to run twice. Backed by `GET /api/admin/shopify/settlement-account` and `POST /api/admin/shopify/settlement-account/assign` (ADMIN; the assignment is audited as a settings change).
- **Effect on reports.** Settlement Center KPIs ("OMS booked" by account) now show Shopify prepaid money under the Shopify account card. Because Shopify orders skip payment verification, that amount appears as *unreviewed*, not *verified*. Shopify payout imports in the Settlement Center can match these payments, because the payout batch's destination account now equals the payment's account. No ledger entries change; the payment's account is not used by any voucher posting.
- **Code.** `ShopifySettlementService` (new), `ShopifyOrderImportService.shopifyPayment`, `PaymentMethodService.linkedTarget`/`findLinkedAccount` (the existing account rule, extracted so order entry and the importer share it), `OrderPaymentRepository.shopifyPaymentTotals`/`assignShopifyPaymentsToAccount`, `PaymentMethodRepository.findFirstByLinkedAccountType…`. `ShopifyOrderImportService` gained a ninth, nullable constructor argument.

### Pin an announcement to the top of the dashboard (V109, deployed 2026-11-02)

Active announcements already appear as a thin banner on every page, but staff dismiss it once and forget it. An admin can now **pin** an announcement so it also shows as a large card at the top of everyone's home screen.

- **Posting.** Announcements → compose form has a new checkbox **"Show at the top of everyone's dashboard"** (off by default). For an announcement that already exists, the row menu has **Pin to dashboard / Remove from dashboard**, and pinned rows show a "Pinned to dashboard" badge. Several announcements can be pinned at once; they are listed newest first.
- **What staff see.** A card with the severity colour, an "Announcement" label, the message (line breaks kept), who posted it and the date. It **cannot be dismissed**, so it stays until the admin removes the pin, hides the announcement (Hide) or deletes it. A hidden (inactive) announcement is not shown even if pinned.
- **Where.** On the user's own home screen, which is `/dashboard` for Admin, Accountant, Salesperson, Team Lead and Packing, `/payments` for the Payment Verifier and `/ca/gst` for the CA (the dashboard redirects those two away). On those screens a pinned announcement is not repeated in the thin banner; on every other page it still appears in the thin banner as before (dismissible).
- **Timing.** The announcement list is read when the app loads, so a user who already has the app open sees a new pin after a page refresh or next sign-in.
- **Data and API.** V109 adds `staff_announcements.show_on_dashboard BOOLEAN NOT NULL DEFAULT FALSE`; existing announcements stay unpinned. `POST /api/admin/announcements` accepts an optional `showOnDashboard`; new ADMIN endpoint `POST /api/admin/announcements/{id}/dashboard?value=true|false`; `GET /api/announcements` (all staff) now includes `showOnDashboard`. Pinning and unpinning are audited as announcement updates.
- **Code.** `Announcement.showOnDashboard`, `AnnouncementService.create/setShowOnDashboard`, `AdminAnnouncementController`, frontend `announcements/` page and the new `PinnedAnnouncementsComponent` rendered by the shell (`admin-shell.component`: `pinnedAnnouncements`, `visibleAnnouncements`).

### Period selectors: Reports default to this month; Leaderboard and Salespeople get real periods (no migration, deployed 2026-11-02)

Three complaints, one root cause each, plus a sweep for the same problems elsewhere.

- **Reports** opened on "Last 30 days". It now opens on **This Month** (the month so far, India time). The preset row gained **Yesterday** and **Last Month** (Today, Yesterday, Last 7 Days, Last 30 Days, This Month, Last Month, All Time, Custom). Picking **Custom** now refreshes as soon as both dates form a valid range (the Generate button still works), a start date after the end date is refused with a message, and a slow answer for an earlier choice can no longer overwrite a newer one.
- **Leaderboard** was hard-wired to the current month in the SQL. `GET /api/my-day/leaderboard` now takes `period` (TODAY, YESTERDAY, LAST_7_DAYS, WEEK, MONTH default, LAST_MONTH, OVERALL, CUSTOM) and, for CUSTOM, `from` and `to` (both days included). The page has a period bar with Today / Yesterday / Last 7 days / This month / Last month / Overall / Custom; badges and the empty state name the period. The response also carries `period`, `periodLabel`, `from`, `to`. The order streak is not tied to the period: it always counts the caller's whole history. Admins' row click opens Orders for the same days (nothing for Overall).
- **Salespeople** (`/salespeople`): the dropdown beside the XLSX/PDF buttons was only an **export** period, and the drawer's tabs filtered a 60-day list in the browser, so choosing Yesterday or Last 7 days changed almost nothing and there was no Custom. Now:
  - a **period bar above the cards** re-fetches `GET /api/admin/salespeople/performance?period=&from=&to=`; every card's Orders and Revenue, the sort order ("Revenue / Orders (selected period)") and the team export follow it. Delivery success stays "every order that has concluded". The card's "View orders" opens Orders for the same days.
  - the **drawer** has its own bar (starting on the directory's choice) that re-fetches `GET /api/admin/salespeople/{id}/performance?period=&from=&to=`. The server returns a `period` block: orders, sales, average order, delivered/failed (of orders placed in the period), a per-day series (quiet days included up to 92 days, active days only beyond that) and the latest 25 orders. The month/lifetime tiles are kept under a "Snapshot" heading and the lead block is labelled "all time".
  - exports (`/performance/export`, `/{id}/performance/export`) accept `from`/`to`, so Custom ranges can be downloaded; the file name carries the dates.
- **One period definition on the server.** `PerformancePeriod` gained LAST_7_DAYS, LAST_MONTH and CUSTOM, plus `resolve(today, from, to)` (a custom range needs both days and `from <= to`, otherwise HTTP 400) and `parse()` (unknown value: 400 `UNKNOWN_PERFORMANCE_PERIOD`). The leaderboard, the Salespeople list/detail and both exports all use it, in Asia/Kolkata. New queries: `MyDayQueryRepository.revenueByCreatorBetween` (replaces the open-ended `...Since`) and `OrderRepository.salespersonPeriodStats`.
- **Shared frontend pieces.** `shared/period-picker.component.ts` (`<admin-period-picker>`) and `shared/period-selection.ts` (types, `periodQuery`, `periodDates`, `customRangeProblem`). A named period is applied immediately; Custom shows two date boxes pre-filled with the month so far and applies when valid, so there is no Apply button to forget.
- **Same problems elsewhere, fixed.** *Dashboard → Orders by delivery partner*: only had This month / Last 30 days / All time, computed the dates in the browser's timezone, and had no Custom; it now uses the shared picker (default This month, India-time dates). *Dashboard channel overview*: default changed from Last 30 days to This month (all other presets and Custom unchanged). *Team Performance*: added Yesterday and Last month.
- **Left as they are on purpose.** *Analytics* keeps its rolling Last 30 days default (rolling windows suit trend comparisons, and its previous-period comparison is awkward on the 1st of a month). The accounting screens (P&L, Day Book, Trial Balance, Cash Flow, GST dashboards) still build their default month in the browser's timezone; this is only wrong for a device outside India time near midnight and was not changed. "This Week" (Monday start) was dropped from the Salespeople UI to match the requested list, but the server still accepts `WEEK`.

### No emails to customers; Team-wise sales gets periods and export (V110, implemented locally, not deployed — 2026-11-02)

Two requests, built together and **not deployed yet**. Deploying applies migration **V110**.

- **Customer emails are off.** `app_settings.customer_emails_enabled` (V110, default `FALSE`) is the switch. While it is off, `MailNotificationPublisher` queues nothing for customers, and `EmailOutboxDrainer` marks any customer email still waiting in the outbox as `SKIPPED` (new terminal outbox status, never retried) instead of sending it. This covers the three customer emails (order approved, dispatched, delivered), including Shopify customers. Not affected: the daily and weekly report to the admins (`ReportRecipients`), the temporary-password email to the admin who resets a user, and the admin-only `POST /api/admin/mail/test`. An admin can turn customer emails on later under **Settings → Automation → Emails to customers** (asks for confirmation when turning on; every change is written to the Audit page). API: `GET`/`PUT /api/admin/settings/customer-emails` with `{ "enabled": true|false }`. `MAIL_MODE=SMTP` stays as it is, so admin emails keep working.
- **Team-wise sales (`/teams-overview`).** The page now has the shared period picker (Today, Yesterday, Last 7 days, This month, Last month, Overall, Custom) and **Excel / PDF download**. `GET /api/admin/dashboard/teams` takes `period`, `from`, `to` (the same `PerformancePeriod` the Leaderboard and Salespeople use; default This month, so the dashboard widget is unchanged). Orders, revenue and average order value follow the period; delivery success, money still to collect, the lead pipeline and leads to call stay "as of now" and the page says so. The member table inside a team panel follows the period too, and the panel has its own period picker and a per-team download. `GET /api/admin/dashboard/teams/export?period=&from=&to=&team=all|unassigned|<teamLeadId>&format=xlsx|pdf` builds three tables (Teams, Salespeople, Leads to call) with `TeamsOverviewExportService`, named like `shifa-team-wise-sales_2026-11-01_to_2026-11-02.xlsx`, and writes a `TEAMS_OVERVIEW_EXPORTED` audit entry. Response field changes: `ordersThisMonth`/`revenueThisMonth` on a team row are now `ordersInPeriod`/`revenueInPeriod`, plus `averageOrderValue`; the response gained `period`, `periodLabel`, `from`, `to`. The query `orderTotalsByCreatorSince` became `orderTotalsByCreatorBetween`.
- **Salesperson drawer opened from a team (`team/team-member-detail.component`)**: for an ADMIN, the Overview tab now starts with a "Sales in a period" block (the same picker, four figures for the chosen period, and Excel / PDF download of that person's report). It calls the existing admin endpoints `GET /api/admin/salespeople/{id}/performance?period=` and `.../performance/export`. A team lead who opens the same drawer from Team Performance keeps the fixed buckets (Today, Last 7 days, This month, Last month) because those admin endpoints are ADMIN-only.
- **Salespeople (`/salespeople`)** already has the period bar (all seven options, directory and drawer) and the XLSX/PDF buttons since the 2 Nov release (`main-JSOK3YBQ.js`); nothing changed there. A browser still showing the old bundle needs a hard refresh (Ctrl+Shift+R).

### Leaderboard UI/UX refresh (implemented locally, not deployed — 2026-11-02)

The `/leaderboard` page was redesigned without changing its API or access rules. It keeps the shared IST period picker, stale-response protection, salesperson self-ranking/streak, and ADMIN-only order drill-down. The page now has a clearer period card with refresh state, a green "Your standing" hero, rank/revenue/streak metrics, a differentiated top-three podium, richer mobile-friendly ranking rows with initials and revenue comparison bars, accessible Space/Enter row actions, explicit unranked state, and a methodology note. Revenue uses the shared INR formatters. No backend or migration change; no production deployment yet.

### Complete Leaderboard ranking and export (implemented locally, not deployed — 2026-11-02)

The Leaderboard now returns every salesperson, including zero-sales rows at the bottom, instead of limiting the response to the top 10. Ranking is deterministic by revenue, orders, name and id. The UI still highlights the top three but shows the full list. Added ADMIN/SALESPERSON Excel and PDF downloads for the selected IST period, including Summary and complete Leaderboard tables. The endpoint is `GET /api/my-day/leaderboard/export?period=&from=&to=&format=xlsx|pdf`; downloads use descriptive `shifa-leaderboard_<range>` filenames and are audited as `LEADERBOARD_EXPORTED`. No migration; not deployed.

### Deployed to Prod (2026-11-03, V110)

Deployed on 2026-11-03 at about 00:00 IST with `deploy\deploy-both.ps1 -SkipBuild -ProdOnly` (demo untouched). The change set is branch `develop` at commit `f78e7e6`: customer-email switch (V110, off by default), Team-wise sales periods and export, complete Leaderboard with Excel/PDF export, plus the earlier announcement pinning and period selectors.

- **Before:** Preflight reported 0 blockers (Flyway V109, all required secrets set, outside the 07:25 to 09:45 IST job window). Baseline: 3,033 orders (total 7,899,385.64, received 4,770,438.34), 5,499 line items, 3,077 payments, 30 users.
- **Build:** full `mvn clean package` (excluding `WhatsappTemplateEncodingIT`) = **1071 tests, 0 failures, 0 errors**; fat JAR 103.4 MB with migrations to V110; admin bundle `main-RKIHR75Q.js` (initial total 1.29 MB, only the known shell CSS budget warning).
- **Deploy:** `aws-apply.sh` guards passed (JAR V110, database V109, 1 migration to apply). Pre-deploy dump `~/shifa-backup-2026-11-02-182908.sql` (37 MB, also in `s3://shifa-db-backup/`); previous JAR kept at `~/shifa-oms.prev-2026-11-02-182908.jar`.
- **After (`prod-check.ps1 -Mode Verify`):** service active, `NRestarts=0`, `/actuator/health` 200, site 200, `/api/states` 401, Flyway validated 110 and applied 1 migration (now V110), "Started Application in 31.2 seconds", no ERROR lines, 0 blockers, 0 warnings. All four invariant lines (orders, line items, payments, users) are identical to the baseline.
- **Effect for users:** customer emails are now off (Settings, Automation, "Emails to customers" switch); admin reports and the temporary-password email still send because they go to admins. Staff need a hard refresh (Ctrl+Shift+R) to load the new bundle through the service worker.

### Morning brief: the admin's 8 AM email, rebuilt (DEPLOYED to Prod 2026-11-03 ~01:10 IST, no migration)

The email sent to the admins every morning (`DailyReportJob`, 08:00 India time, and `POST /api/admin/reports/daily-digest/run?date=`) was a flat list that also mixed today's figures into a report about yesterday. It is now a "morning brief" about the previous day. Recipients, schedule, the `REPORT_DIGEST_ENABLED` switch and the endpoint are unchanged; the weekly report keeps its older layout. No migration.

What it shows, top to bottom:
- **Headline**: total sales and orders for the day, average order value, and the change against the day before and the same weekday a week earlier. Three tiles: collected in banks, to collect on delivery, and the month so far.
- **Sales by source**: Portal, Shopify and Store (POS), with orders, sales, share, and cash still to be collected on delivery on those orders. Same split as the dashboard (`DashboardChannel`).
- **Collected by bank / QR account**: money recorded on orders that day, per receiving account (the Settlement Center's "OMS booked"), with payments awaiting verification and rejected ones called out. Cash and older payments with no account appear as "Cash / no account recorded".
- **Cash on delivery**: due on the day's orders, received that day (courier payouts and in-house collections), and everything still to collect across all dates (customers, courier, claims), with the part older than the courier deadline.
- **Top 3 salespeople** of the day, with any bonus they earned.
- **Daily sales incentive**: the slabs in force that day, how many salespeople earned a bonus, who, how much, and the month's total so far. If no program was active it says so; if nobody reached the first slab it names the closest.
- **More from the day**: new and returning customers, orders delivered, failed or returned, rejected or cancelled, the three best-selling products. A short "waiting for you" list (approvals, payments to verify, failed deliveries, stuck shipments).

How it is built: `MorningBriefService` gathers the figures (`MorningBriefQueryRepository` holds the JPQL; the top salespeople and the incentive come from the same tracker as the Daily Sales & Incentives page, so the numbers agree). `MorningBrief` is plain data, `MorningBriefRenderer` turns it into HTML and plain text (Indian digit grouping, 640px card that shrinks to a phone, long names escaped). A section that fails to load is shown as "could not be loaded today" and never stops the email. A manual run for another date says "on Fri 31 Oct" instead of "yesterday". Both the schedule and "yesterday" are pinned to India time.

Choices to confirm with the client: "collected in which bank" is the money recorded on orders per account; cash that couriers pay out is not tied to any bank account in the data, so it is shown as one total. Top salespeople and the incentive count the SALESPERSON role only (the same roster as the incentive tracker), and Shopify orders, which have no salesperson, appear in the source split but not in the ranking.

Validation of the morning brief: full backend suite `mvn clean package` = **1071 tests, 0 failures** (2026-11-03, after the change). A design preview of the email is in `docs/Morning-Brief-Email-Preview-2026-11-03.pdf`; it is built from the local copy of the data (not live figures) and uses the example incentive slabs (₹25,000 → ₹500, ₹35,000 → ₹750) because no program is saved yet.

**Deployed 2026-11-03 at about 01:10 IST** with `deploy\deploy-both.ps1 -SkipBuild -ProdOnly` (demo untouched). Preflight: 0 blockers, database already at V110, so no migration ran. Pre-deploy dump `~/shifa-backup-2026-11-02-194008.sql` (37 MB, also in `s3://shifa-db-backup/`); previous JAR kept at `~/shifa-oms.prev-2026-11-02-194008.jar`. Verify: service active, `NRestarts=0`, health 200, site 200, `/api/states` 401, "Started Application in 29.32 seconds", no ERROR lines, 0 blockers and 0 warnings; orders (3,035), line items (5,501), payments (3,079) and users (30) identical to the baseline taken minutes before. The frontend bundle is unchanged (`main-RKIHR75Q.js`). The first real brief goes out at 08:00 IST for the previous day; `POST /api/admin/reports/daily-digest/run` (as an admin) sends one on demand. Until slabs are saved under Settings → Incentives the incentive section says no program was active.

### Admin report recipients + "Send now" (V111, implemented locally, not deployed — 2026-11-03)

**Why the 8 AM email missed some admins.** On 3 Nov the job sent to all 3 recipients it had (`sent to 3 of 3`, no errors). Prod has 5 active admin users and 2 of them (`admin` and `8109725353`) have no email on their profile, and the report only goes to admins that have one. So nothing failed; those two were never on the list.

**What was added** (Settings → new **Email** tab):
- **Admin report emails** list: every active admin user first (with a clear "No email on the profile" and an **Add email** shortcut), then the addresses added here, then any set on the server (`REPORT_DIGEST_TO`, read only). Each line shows whether it gets the report, with the reason when it does not.
- **Add another address** (name optional + email). It is saved in the new `report_recipients` table (**V111**) and used for **every later report** (the 8 AM brief and the weekly report) until paused or removed. Pause/resume and remove are on each added line. Admin users need no entry: they are read from the user table every time.
- **Send now** button: opens a dialog with a date (default yesterday), all admin users and added addresses ticked, **Select all / Clear**, individual tick boxes, and an **Add another address** form that saves the address and ticks it. It sends the same morning brief the 8 AM job sends, only to the ticked people, and shows how many were sent and which addresses failed and why.
- The existing **Emails to customers** switch moved from the Automation tab to this Email tab.

**Rules.** One plain valid address per entry (no `Name <addr>` form, no lists); duplicates are refused case-insensitively, including an address an admin already receives under their profile; "Send now" accepts only addresses that are on the list (it cannot be used to mail strangers), at most 50 at a time, for today or an earlier day. Changes and manual sends are audited (`REPORT_RECIPIENT_ADDED/UPDATED/REMOVED`, `REPORT_SENT_MANUALLY`).

**API (ADMIN only):** `GET /api/admin/report-recipients`, `POST /api/admin/report-recipients` `{email,name}`, `PUT /api/admin/report-recipients/{id}/active` `{active}`, `DELETE /api/admin/report-recipients/{id}`, `POST /api/admin/report-recipients/send` `{date,emails}`. Every change returns the refreshed list. The existing `POST /api/admin/reports/daily-digest/run` is unchanged and now also reaches the added addresses.

**Code:** `mail/ReportRecipient` (+ repository), `ReportRecipients` (now returns entries with their source and has `candidates()` for hand sends), `ReportRecipientService`, `ReportRecipientController`, `DailyReportService.sendMorningBriefTo`; frontend `settings/report-recipients-manager.component.ts` + `report-recipients.service.ts`. `deploy/prod-check.sh` expects V111.

**Validation:** a temporary test against the real local MySQL (V111 applied) covered listing, adding, every rejection rule, pause/resume, sending to chosen addresses, a simulated mail failure, refused strangers/future day/paused addresses, and removal (all passed; the test and its data were removed). Deploying applies migration V111.
