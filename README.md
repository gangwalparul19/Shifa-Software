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
| Security   | Spring Security + JWT (access/refresh), role-based (`@PreAuthorize`)        |
| Frontend   | Angular 21 workspace — `admin` app + shared `core` & `ui` libraries         |
| Admin UI   | Tabler light theme (`@tabler/core`), Inter fonts, brand green `#1F5D3F`, ApexCharts |
| Docs / PDF | Apache POI (Excel), OpenPDF (invoices/labels), ZXing (barcodes)             |
| Cloud      | OCI Always Free tier (A1 VM + self-hosted MySQL), OCI Object Storage SDK    |

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
│  │  └─ db/migration/                # Flyway V1..V27 (see §6)
│  └─ pom.xml
├─ frontend/                    # Angular 21 workspace
│  ├─ angular.json                    # projects: admin, core, ui
│  ├─ package.json                    # npm scripts (see §3)
│  └─ projects/
│     ├─ admin/                       # the admin dashboard app (port 4300)
│     ├─ core/                        # shared services/models (ApiClient, guards, Role)
│     └─ ui/                          # shared UI component library
├─ deploy/                      # OCI deployment assets (nginx, systemd, env example, scripts)
├─ DEPLOYMENT.md                # step-by-step OCI Always Free deployment guide
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
| `packing`         | Barcode scan / packing confirmation. `/api/packing` |
| `courier`         | Courier assignment (mock), tracking, webhooks, shipping labels. `/api/track`, `/api/webhooks/courier`, `/api/admin/labels/shipping` |
| `label`           | Internal label PDFs. `/api/admin/labels/internal` |
| `invoice`         | Per-order invoice PDF (plain + GST tax invoice) + invoice numbering |
| `reconciliation`  | COD/settlement, receivables. `/api/recon` |
| `reporting`       | Excel / PDF / Vyapar exports. `/api/reports` |
| `finance`         | Expenses + Profit & Loss. `/api/admin/expenses`, `/api/admin/finance` |
| `procurement`     | Suppliers + purchase orders. `/api/admin/suppliers`, `/api/admin/purchase-orders` |
| `returns`         | Order returns/refunds. `/api/admin/returns` |
| `crm`             | Customer list derived from orders. `/api/admin/customers` |
| `dashboard`       | Metrics + SSE event stream. `/api/admin/metrics`, `/api/admin/events` |
| `adminnotification`| Durable admin alerts. `/api/admin/notifications` |
| `audit`           | "Who did what" audit trail. `/api/admin/audit` |
| `settings`        | Company + GST + invoice settings (GST on/off). `/api/admin/settings` |
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
scoped to `SALESPERSON`/`ADMIN` — this replaced the retired public catalog endpoint.

---

## 5. Admin App Pages (`frontend/projects/admin/src/app`)

Routing in `app.routes.ts`, shell/nav in `shell/admin-shell.component.ts`. Guards enforce roles
(`staffGuard`, `adminOnlyGuard`, `salespersonGuard`, `accountantGuard`, `packingGuard`).

| Route | Page | Access |
|-------|------|--------|
| `/dashboard` | KPIs, sparklines, welcome hero | all staff |
| `/approval-queue` | Approve/reject pending orders | ADMIN |
| `/orders`, `/orders/new` | Orders list / new order entry | staff / SALESPERSON+ADMIN |
| `/products` | Catalog (manage: ADMIN) | ADMIN + SALESPERSON (read-only) |
| `/inventory` | Stock | ADMIN |
| `/customers` | CRM (SALESPERSON sees only their own customers) | ADMIN + ACCOUNTANT + SALESPERSON |
| `/returns` | Returns/refunds | ADMIN+ACCOUNTANT |
| `/notifications`, `/audit` | Alerts / audit log | ADMIN |
| `/suppliers`, `/purchase-orders` | Procurement | ADMIN |
| `/expenses`, `/finance/pnl` | Finance | ADMIN+ACCOUNTANT |
| `/packing` | Barcode scan + handover/dispatch | PACKING_USER+ADMIN |
| `/reconciliation`, `/reports` | Settlement + exports | ADMIN+ACCOUNTANT |
| `/settings`, `/users` | Config + staff users | ADMIN |

### Mobile-first UI redesign (spec `mobile-ui-redesign`)
The admin app was redesigned mobile-first to a client wireframe (`docs/wireframe.jpeg`), reusing the
Tabler theme + Shifa green + ApexCharts. The shell is a **solid dark-green banner top bar + a
top-bar hamburger (full role-filtered menu, incl. the detailed dashboard) + a persistent role-aware
bottom tab bar** carrying the four most-used destinations per role (Salesperson: New Order/Orders/
Customers/Products; Packer: Packing/Handover/Dispatch/Orders; Accountant: Reconcile/Reports/Expenses/
Orders; Admin: Approvals/Orders/Products/Reports). Every screen is single-column at 360px, uses
cards over wide tables below 768px, KPI tiles (green icon chip + value + delta), colored status
pills, ≥44px touch targets, real product images (served from `admin/public/products/`), and the
order/product detail + all operational/config/auth pages follow the same language. Presentation-only
— it reuses existing endpoints (plus the additive `GET /api/admin/products/{id}/stats` and the order
line `imageKey` / `discountAmount` fields).

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

Target is **OCI Always Free** (A1 VM + self-hosted MySQL 8, Nginx reverse proxy, systemd service).
Full step-by-step in `DEPLOYMENT.md`. Assets in `deploy/`:
`nginx-shifa.conf`, `shifa-oms.service`, `shifa.env.example`, `build-and-deploy.sh`,
`apply-on-vm.sh`, `make-bundle.ps1`, `package-local.ps1`. Prod `apiBaseUrl` is `''` (same-origin
behind Nginx). Prod DB name defaults to `shifa_dashboard` (override via `DB_NAME`).

---

## 10. Branches

- `feature/shifa-backend` — prior mainline.
- `dashboard-only` — **current** working branch for the dashboard-only pivot.
