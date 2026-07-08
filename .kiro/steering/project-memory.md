---
inclusion: always
---

# Shifa OMS — Project Memory (keep this updated)

> Fast-reference memory so we don't re-scan the codebase each session. **Whenever the project
> changes (modules added/removed, migrations, run commands, conventions, branch), update this file
> AND the matching section of `README.md` in the same change.** Prefer editing this over rediscovering.

## What this project is
Dashboard-only **Order Management System** for Shifa Herbal Remedies. The public storefront was
removed (store now on **Shopify**). Remaining scope: admin dashboard + salesperson order entry
(salesperson captures customer details). Full details in `README.md`.

## Stack
- Backend: Java 21 (lang level) / Spring Boot 3.3.5 modular monolith; MySQL 8 + Flyway (`ddl-auto: validate`); Spring Security + JWT/RBAC.
- Frontend: Angular 21 workspace — `admin` app (port 4300) + `core`/`ui` libs. Tabler light theme, brand green `#1F5D3F`, ApexCharts.
- Deploy target: OCI Always Free (see `DEPLOYMENT.md`). Integrations (courier/WhatsApp) are mock.

## Run commands (Windows/cmd — use explicit paths, NOT tool cwd)
- Backend (8080): `mvn -f "backend/pom.xml" -DskipTests spring-boot:run`
- Admin UI (4300): `npm --prefix frontend run start:admin`
- Backend tests: `mvn -f "backend/pom.xml" test`  |  Admin build: `npm --prefix frontend run build:admin`
- DB: MySQL 8 at `C:\Program Files\MySQL\MySQL Server 8.0\bin\` (not on PATH), creds `root`/`root@123`, DB `shifa_dashboard`.

## Seeded demo logins
`admin`/`admin123` (ADMIN), `accountant`/`admin123`, `sales1`/`admin123`, `sales2`/`admin123` (SALESPERSON), `packer`/`packer123` (PACKING_USER).

## Order lifecycle (role-based workflow — implemented)
Flow: PENDING_ADMIN_APPROVAL → APPROVED → LABEL_GENERATED → PACKED → **HANDED_TO_DELIVERY** → COURIER_ASSIGNED → DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → {DELIVERED→(CLOSED|COD_COLLECTED), **CUSTOMER_REJECTED**, **DELIVERY_FAILED**, RTO, COURIER_LOST} (+ REJECTED/CANCELLED). Roles: ADMIN/SALESPERSON/PACKING_USER/ACCOUNTANT (no shipping role; packer+admin do handover/dispatch).
- Central `order/OrderWorkflowService.applyTransition(order,target,Actor)` = authorize (`statemachine/TransitionAuthority`, 403) → legality (`OrderStatusStateMachine`, 409) → status + one history row → audit → `notification/NotificationMatrix` fan-out. Actor = human role or SYSTEM (courier).
- Endpoints: `POST /api/packing/{id}/handover`, `POST /api/packing/{id}/dispatch` (PACKING_USER/ADMIN); `GET /api/dashboard/summary` (role-shaped, all staff); `GET /api/notifications` (staff, per-user/role); reports ORDERS_BY_LEAD_SOURCE/STATUS/SALESPERSON, DELIVERY_OUTCOME.
- NotificationMatrix = single source (WhatsApp per-step to customer, email ONLY on APPROVED/DISPATCHED/DELIVERED, in-app to roles/creator) via outbox; `mail/EmailOutboxDrainer` mirrors WhatsApp drainer. Courier tokens customer_rejected/refused→CUSTOMER_REJECTED, delivery_failed/failed/undelivered→DELIVERY_FAILED.
- Order entry: `orders.lead_source`(+note)/`customer_email` (LeadSource enum WHATSAPP/INSTAGRAM/FACEBOOK/GOOGLE/OFFLINE/OTHER), distinct from OrderSource. Frontend: role-aware DashboardComponent, mobile-first pass, per-user notification bell.
- Migrations V23 (lead_source/note/customer_email) + V24 (admin_notifications.recipient_role/recipient_user_id). Backend 423 tests pass; admin builds. Spec: `.kiro/specs/role-based-order-workflow/`.

## Mockup-parity read fields (additive, no migration)
- Order detail (`OrderResponse`): line items carry `imageKey` (product's first PUBLISHED image, batch-loaded in `OrderService.getOrder` via `ProductImageRepository.findPublishedByProductIds` — no N+1); `discountAmount` (from `orders.discount_amount` V6, default 0.00). Admin orders drawer: per-item thumbnail via `resolveImageUrl`, "Discount" totals line shown only when > 0.
- Product stats: `GET /api/admin/products/{id}/stats` → `{salesThisMonth, ordersThisMonth}` for the CURRENT month, excluding REJECTED/CANCELLED (matches P&L revenue). Auth `hasAnyRole('ADMIN','SALESPERSON')`. Inversion: product `ProductSalesLookup` iface impl by order `OrderProductSalesLookup` (Clock-based month window) over `OrderRepository.productSalesStats` native aggregate. Admin product-detail "Sales Overview" shows This Month ₹ / Orders. 442 backend tests pass; admin builds.

## Mobile-first UI redesign (spec `mobile-ui-redesign`) — implemented
Admin app redesigned to a client wireframe (`docs/wireframe.jpeg`), mobile-first, reusing Tabler + Shifa green + ApexCharts. Shell = dark-green banner top bar + hamburger (full role menu incl. detailed dashboard) + persistent role-aware BOTTOM TAB BAR (4 tabs/role) in `shell/admin-shell.component.ts`. Every screen restyled: cards over tables <768px, KPI tiles, status pills, ≥44px targets, real product images copied to `frontend/projects/admin/public/products/` and resolved via `shared/product-image.util.ts` (`resolveImageUrl`). Order detail = green header + status pill + avatar/call + map-pin + item thumbnails + totals(subtotal/discount)/payment + download invoice. All operational/config/auth pages done too.
- Salesperson READ access: `AdminProductController` GET endpoints + `CustomerController` allow SALESPERSON (customers scoped to own via SalespersonScopeResolver); product create/update stay ADMIN-only; frontend hides mutation affordances for non-admins (`canManage`).
- Parity backend additions (no migration): order line `imageKey` (primary published product image, batch-loaded in `OrderService.getOrder`), `OrderResponse.discountAmount`, and `GET /api/admin/products/{id}/stats` (per-product current-month revenue + order count via `OrderProductSalesLookup`/`ProductSalesLookup` inversion). Backend 442 tests pass; admin builds.

## Backend modules kept (`com.shifa.oms.*`)
auth, order (+`GET /api/orders/products` picker), statemachine, product, inventory, packing, courier,
label, invoice, reconciliation, reporting, finance, procurement, returns, crm, dashboard,
adminnotification, audit, settings, search, agent, notification, mail, platform, common.
**Removed in pivot:** account, review, payment, coupon, and public checkout/catalog/storefront-config controllers.

## Admin pages
dashboard, approval-queue, orders(+/new), products, inventory, customers, returns, notifications,
audit, suppliers, purchase-orders, expenses, finance/pnl, packing, reconciliation, reports, settings, users.
Routes: `frontend/projects/admin/src/app/app.routes.ts`; nav: `shell/admin-shell.component.ts`.

## Database / migrations
- Flyway dir: `backend/src/main/resources/db/migration`. **Never edit an applied migration; add a new versioned one.**
- `V21` drops storefront tables; `V22` seeds a self-contained demo dataset (all profiles).
- `V23` adds `orders.lead_source`/`lead_source_note`/`customer_email` (+`ix_orders_lead_source`); `V24` adds `admin_notifications.recipient_role`/`recipient_user_id` (+`ix_admin_notifications_recipient`). Both additive/nullable (role-based-order-workflow).
- Start against an **empty** `shifa_dashboard` (V22 uses explicit IDs). If half-migrated, drop & recreate the DB first.
- Storefront-added columns on orders/products/users are intentionally kept (still mapped by entities).

## Gotchas (bit us before)
- Exit codes spuriously return **-1** even on success → judge by printed output (BUILD SUCCESS / bundle complete / test summary).
- **Java 25 runtime**: Mockito can't mock concrete classes → use real instances / recording subclasses in tests.
- Spring service with **two constructors** (one takes `Clock` for tests) → add `@Autowired` to the primary constructor or startup fails ("No default constructor").
- Don't rely on tool `cwd` (PowerShell `cd` prefix breaks cmd) — use `-f`/`--prefix` explicit paths.
- Start long-running processes (dev server/watchers) as background processes.

## Branch
Current working branch: `dashboard-only` (pivot committed; not pushed unless stated). Prior: `feature/shifa-backend`.
