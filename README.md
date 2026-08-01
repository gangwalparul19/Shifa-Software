# Shifa Herbal Remedies — Order Management System (OMS)

Internal **admin dashboard + order management platform** for Shifa Herbal Remedies. The public
storefront was retired (the store now runs on **Shopify**); this project is now a **dashboard-only**
back office: staff manage products, salespeople punch orders, and the order lifecycle (approval →
packing → courier → delivery → settlement) is tracked end to end.

> New to the repo? This README is the single source of truth for architecture, how to run things,
> and the important gotchas. Read it before scanning the codebase. A condensed "memory" for fast
> context lives in `.kiro/steering/project-memory.md` and is kept in sync with this file.

---

## 1. Tech Stack

| Layer      | Technology                                                                 |
|------------|----------------------------------------------------------------------------|
| Backend    | Java 21 (language level), Spring Boot 3.3.5 — modular monolith             |
| Persistence| MySQL 8, Spring Data JPA (Hibernate), Flyway migrations (`ddl-auto: validate`) |
| Security   | Spring Security + JWT (access/refresh), role-based (`@PreAuthorize`); public login/refresh/register calls are bearer-free and CORS permits the production admin origin `https://shifa.weblithic.online` |
| Frontend   | Angular 21 workspace — `admin` app + shared `core` & `ui` libraries         |
| Admin UI   | Tabler light theme (`@tabler/core`), Inter fonts, brand green `#1F5D3F`, ApexCharts |
| Docs / PDF | Apache POI (Excel), OpenPDF (invoices/labels), ZXing (barcodes)             |
| Cloud      | AWS EC2 (Ubuntu + self-hosted MySQL 8), Nginx/systemd, Amazon S3 file storage |

Integrations (courier, WhatsApp, online payments) were **sandbox/mock** and payment/coupon flows
have been removed in the dashboard-only pivot (see §7).

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
│  │  └─ db/migration/                # Flyway V1..V48 (see §6)
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
- `test` — Angular unit tests; `test:pbt` — property-based tests (jest/fast-check)

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
| `reporting`       | 17 report views — Sales, Orders, **Money & Receivables** (PAYMENTS, OUTSTANDING dues, COD_REMITTANCE) and **Operations** (EXPENSES, PURCHASE_ORDERS, RETURNS, STOCK; ADMIN/ACCOUNTANT only) — with Excel / PDF / Vyapar exports. `/api/reports/{type}` |
| `finance`         | Expenses + Profit & Loss. `/api/admin/expenses`, `/api/admin/finance` |
| `procurement`     | Suppliers + purchase orders. `/api/admin/suppliers`, `/api/admin/purchase-orders` |
| `returns`         | Order returns/refunds. `/api/admin/returns` |
| `crm`             | Customer records derived from orders + **Customer 360**: list/detail (`/api/admin/customers`), profile/risk/tags/notes (`/{mobile}/profile`, `/{mobile}/risk`, `/{mobile}/notes`, `/{mobile}/tags`) |
| `performance`     | **Salesperson 360**: leaderboard + per-salesperson performance detail + monthly sales targets. `/api/admin/salespeople/performance`, `/api/admin/salespeople/{id}/performance`, `/api/admin/salespeople/targets`. **Team-lead performance** (team KPIs + leaderboard + lead-source conversion): `GET /api/team/performance` (TEAM_LEAD+ADMIN) |
| `analytics`       | Customer **retention** cohorts + revenue/demand **forecast** for admin dashboards. `/api/admin/analytics/retention`, `/api/admin/analytics/forecast` |
| `announcement`    | Staff announcement banners. `GET /api/announcements` (staff), admin CRUD `/api/admin/announcements` |
| `push`            | Browser Web Push (VAPID, config-gated). `/api/notifications/push/{public-key,subscribe,unsubscribe}` |
| `dashboard`       | Metrics + SSE event stream. `/api/admin/metrics`, `/api/admin/events` |
| `adminnotification`| Durable staff alerts (role/user-addressed). `/api/notifications` (staff), `/api/admin/notifications` |
| `audit`           | "Who did what" audit trail. `/api/admin/audit` |
| `settings`        | Company + GST + invoice settings (GST on/off). `/api/admin/settings` |
| `geo`             | Delivery-state master list for the order-entry typeahead. `GET /api/states` (staff), admin CRUD `/api/admin/states` |
| `search`          | Global admin search. `/api/admin/search` |
| `agent`           | Salesperson lookup helpers. `/api/agent` |
| `notification`    | WhatsApp/outbox notifications (mock), transactional outbox drainer |
| `mail`            | SMTP/mock email. `/api/admin/mail` |
| `platform`        | Cross-cutting: storage (local/OCI), backups, outbox infra. `/api/admin/backups` |
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

The Orders **list** filters by a **grouped status selector** (both a quick tab strip and the advanced-panel
dropdown) instead of the raw ~18 statuses: the `order/OrderStatusGroup` enum clubs them into 9 business
stages — Pending Approval, Packaging, Label Generated, Awaiting Handover, Awaiting Dispatch, In Transit,
Completed, Cancelled, Failed/Returned (labels match the Packing queue). Filtering is server-side via
`GET /api/admin/orders?statusGroup=…` (expands to `orderStatus IN (members)`), so it is correct across
pagination and search. The exact `?status=` param + saved views still work (mapped onto their group).

---

## 5. Admin App Pages (`frontend/projects/admin/src/app`)

Routing in `app.routes.ts`, shell/nav in `shell/admin-shell.component.ts`. Guards enforce roles
(`staffGuard`, `adminOnlyGuard`, `salespersonGuard`, `accountantGuard`, `packingGuard`,
`paymentVerifierGuard`).

| Route | Page | Access |
|-------|------|--------|
| `/dashboard` | KPIs, sparklines, welcome hero | all staff |
| `/approval-queue` | Approve/reject pending orders | ADMIN |
| `/orders`, `/orders/new` | Orders list / new order entry | staff / SALESPERSON+ADMIN |
| `/products` | Catalog (manage: ADMIN) | ADMIN + SALESPERSON (read-only) |
| `/inventory` | Stock | ADMIN |
| `/customers` | CRM + Customer 360 drawer (metrics, delivery-risk, tags, notes, order history; SALESPERSON sees only their own) | ADMIN + ACCOUNTANT + SALESPERSON |
| `/returns` | Returns/refunds | ADMIN+ACCOUNTANT |
| `/notifications`, `/audit` | Alerts / audit log | ADMIN |
| `/announcements` | Post/hide/delete staff announcement banners | ADMIN |
| `/suppliers`, `/purchase-orders` | Procurement | ADMIN |
| `/expenses`, `/finance/pnl` | Finance | ADMIN+ACCOUNTANT |
| `/packing` | **Scan & Move** camera/manual barcode preview + explicit Pack/Handover/Dispatch confirmation, handover popup (name/phone), multi-label print, and multi-pack | PACKING_USER+ADMIN |
| `/payments` | Payment verification dashboard — review prepaid screenshots, verify/reject | PAYMENT_VERIFIER+ADMIN |
| `/team` | Assign salespeople to a team lead (drives team-scoped order visibility) | ADMIN |
| `/team-performance` | Team performance rollup — clickable Salespeople KPI, team-scoped salesperson list, and profile/performance drill-down (lifetime, today, last week/month, delivery/COD/leads, daily activity, recent orders) | TEAM_LEAD+ADMIN |
| `/reconciliation` | COD settlement | ADMIN+ACCOUNTANT |
| `/reports` | Grouped report catalogue (Sales / Orders / Money & Receivables / **Operations**: expenses, purchase orders, returns, stock) — incl. a **Finance** tab: outstanding dues chase list, COD pending from courier, daily payments; Excel/PDF export | ADMIN+ACCOUNTANT |
| `/analytics` | Sales targets, retention cohorts, revenue/demand forecast | ADMIN |
| `/settings`, `/users` | Config + staff users | ADMIN |
| `/salespeople` | Salesperson onboarding + ID verification directory + **Salesperson 360** performance (leaderboard KPIs, sort, per-person performance drawer, monthly targets) | ADMIN |

The shell hamburger nav groups links under **collapsible, collapsed-by-default named groups**
(accordion): only "Shifa Dashboard" is standalone; the rest sit under **CRM** (Leads, Due follow-ups,
Customers, Salespeople), **Analytics & Reports** (Reports, Analytics, Insights), and
**Account & Settings** (My Profile, Settings, Users, …). The role-aware bottom tab bar is unchanged.

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
- **Installable PWA + offline order capture (8.1)** — Angular service worker (`ngsw-config.json`,
  registered in production via `provideServiceWorker`), `manifest.webmanifest` + icon. `PwaService`
  drives an offline chip, an **Install** button and an **update-ready** banner in the shell.
  `OfflineOrderQueueService` queues COD (no-payment) orders in `localStorage` when offline and
  auto-syncs to `POST /api/orders` on reconnect (paid orders / lead-conversion require connectivity).
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
`/analytics` (ADMIN) has three tabs backed by the `performance` + `analytics` modules:
- **Sales targets (6.1)** — per-salesperson monthly targets (`sales_targets`, `V38`) with achieved vs
  target progress, set/edit from the tab and surfaced on Salesperson 360.
- **Retention (6.3)** — repeat-customer cohorts (new vs returning, repeat rate) from order history.
- **Forecast (6.5)** — next-period revenue + product-demand projection from trailing sales.
- Dashboard tiles are user-configurable (show/hide, persisted in `localStorage`, 6.4).
**Salesperson 360** (leaderboard + per-person KPIs: orders, revenue, conversion, delivery success,
target progress) lives on the `/salespeople` page so admins can track and compare team performance.

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
- `V28` — `stored_files` (DB-backed binary store for payment screenshots; prod `app.storage.provider=DB`).
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
  payloads, and ensures the immutable V22/V27 seed values also end as `REDISPATCH`. The existing
  `CLAIM_RECEIVABLE`, cleared customer outstanding, and claim-required alert semantics are unchanged. **V48 is the
  highest migration.**

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

Hosted on **one AWS EC2 instance** (Ubuntu + self-hosted MySQL 8, Nginx reverse proxy, systemd
service, S3 for file storage). Admin app is served at `/`; API at `/api/` → Spring Boot on :8080.
**Follow `DEPLOYMENT.md` — it is the single canonical guide.** Routine redeploy is one command:
`powershell -ExecutionPolicy Bypass -File deploy\push-to-aws.ps1 -KeyPath "<your.pem>"`.
Assets in `deploy/`: `push-to-aws.ps1` (build + upload + apply), `aws-apply.sh` (server-side
backup → swap → restart), `nginx-shifa.conf`, `shifa-oms.service`, `shifa.env.example`. Prod
`apiBaseUrl` is `''` (same-origin behind Nginx). Prod DB name defaults to `shifa_dashboard`
(override via `DB_NAME`). **Last verified deployment: 2026-07-30** — production backup
`~/shifa-backup-2026-07-30-143710.sql`; Flyway applied V48 (renaming persisted `COURIER_LOST`
values to `REDISPATCH`), Spring Boot started on `:8080`, `https://shifa.weblithic.online/` returned
`200`, and unauthenticated `/api/states` correctly returned `401`.

---

## 10. Branches

- `feature/shifa-backend` — prior mainline.
- `dashboard-only` — **current** working branch for the dashboard-only pivot.
