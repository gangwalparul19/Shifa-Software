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
V27 test seed adds `sales01`..`sales20`, `accountant2`, `packer2`, `admin2` — **all `admin123`** (see `docs/test-data-guide.html`).

## Order lifecycle (role-based workflow — implemented)
Flow: PENDING_ADMIN_APPROVAL → APPROVED → LABEL_GENERATED → PACKED → **HANDED_TO_DELIVERY** → COURIER_ASSIGNED → DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → {DELIVERED→(CLOSED|COD_COLLECTED), **CUSTOMER_REJECTED**, **DELIVERY_FAILED**, RTO, COURIER_LOST} (+ REJECTED/CANCELLED). Roles: ADMIN/SALESPERSON/PACKING_USER/ACCOUNTANT (no shipping role; packer+admin do handover/dispatch).
- Central `order/OrderWorkflowService.applyTransition(order,target,Actor)` = authorize (`statemachine/TransitionAuthority`, 403) → legality (`OrderStatusStateMachine`, 409) → status + one history row → audit → `notification/NotificationMatrix` fan-out. Actor = human role or SYSTEM (courier).
- Endpoints: `POST /api/packing/{id}/handover`, `POST /api/packing/{id}/dispatch` (PACKING_USER/ADMIN); `GET /api/dashboard/summary` (role-shaped, all staff); `GET /api/notifications` (staff, per-user/role); reports ORDERS_BY_LEAD_SOURCE/STATUS/SALESPERSON, DELIVERY_OUTCOME.
- NotificationMatrix = single source (WhatsApp per-step to customer, email ONLY on APPROVED/DISPATCHED/DELIVERED, in-app to roles/creator) via outbox; `mail/EmailOutboxDrainer` mirrors WhatsApp drainer. Courier tokens customer_rejected/refused→CUSTOMER_REJECTED, delivery_failed/failed/undelivered→DELIVERY_FAILED.
- Order entry: `orders.lead_source`(+note)/`customer_email` (LeadSource enum WHATSAPP/INSTAGRAM/FACEBOOK/GOOGLE/OFFLINE/OTHER), distinct from OrderSource. Frontend: role-aware DashboardComponent, mobile-first pass, per-user notification bell.
- Migrations V23 (lead_source/note/customer_email) + V24 (admin_notifications.recipient_role/recipient_user_id). Backend 423 tests pass; admin builds. Spec: `.kiro/specs/role-based-order-workflow/`.

## Packing workflow visibility + label printing (no migration) — implemented
Packing was a blind scan box only. Labels ARE auto-generated on approval (`LabelService.generateInternalLabelOnApproval`
→ Code128 barcode of the order code + customer/address/items/COD, stored `labels/internal/<code>.pdf`, order → LABEL_GENERATED),
but nothing surfaced them. Added:
- Backend `GET /api/packing/queue` (`PackingController`, `hasAnyRole('PACKING_USER','ADMIN')`) → `PackingQueueResponse`
  {awaitingPacking (LABEL_GENERATED), awaitingHandover (PACKED), awaitingDispatch (HANDED_TO_DELIVERY)}, each oldest-first
  via new `OrderRepository.findByOrderStatusOrderByCreatedAtAsc`. `PackingService.queue()` maps to `OrderSummaryResponse`.
- `LabelController` (`/api/admin/labels/internal/{id}` + `/bulk`) opened from ADMIN-only to `hasAnyRole('ADMIN','PACKING_USER')`
  so the packer can (re)print. Label PDF is regenerated deterministically from current order state.
- Frontend Packing page (`packing/scan.component.*`) now renders three work-queue lists (Orders to pack / Awaiting handover /
  Awaiting dispatch) with per-order **Print label** (opens the barcode PDF via `PackingService.label(id)` blob) + primary action
  (Mark packed = scan by order code / Handover / Dispatch); keeps the scanner box + session "Ready to move" + Recent scans.
  `PackingService.queue()`/`label()` added; `packing.model.ts` `PackingQueue`.
- Order detail drawer gains an ADMIN **Print Label** button (`OrdersService.label(id)`, `orders.component` `printLabel`/`canPrintLabel`).
Backend 493 tests still pass (Packing/Label/guard suites green); admin builds clean.

## Mockup-parity read fields (additive, no migration)
- Order detail (`OrderResponse`): line items carry `imageKey` (product's first PUBLISHED image, batch-loaded in `OrderService.getOrder` via `ProductImageRepository.findPublishedByProductIds` — no N+1); `discountAmount` (from `orders.discount_amount` V6, default 0.00). Admin orders drawer: per-item thumbnail via `resolveImageUrl`, "Discount" totals line shown only when > 0.
- Product stats: `GET /api/admin/products/{id}/stats` → `{salesThisMonth, ordersThisMonth}` for the CURRENT month, excluding REJECTED/CANCELLED (matches P&L revenue). Auth `hasAnyRole('ADMIN','SALESPERSON')`. Inversion: product `ProductSalesLookup` iface impl by order `OrderProductSalesLookup` (Clock-based month window) over `OrderRepository.productSalesStats` native aggregate. Admin product-detail "Sales Overview" shows This Month ₹ / Orders. 442 backend tests pass; admin builds.

## Mobile-first UI redesign (spec `mobile-ui-redesign`) — implemented
Admin app redesigned to a client wireframe (`docs/wireframe.jpeg`), mobile-first, reusing Tabler + Shifa green + ApexCharts. Shell = dark-green banner top bar + hamburger (full role menu incl. Shifa dashboard) + persistent role-aware BOTTOM TAB BAR (4 tabs/role) in `shell/admin-shell.component.ts`. Every screen restyled: cards over tables <768px, KPI tiles, status pills, ≥44px targets, real product images copied to `frontend/projects/admin/public/products/` and resolved via `shared/product-image.util.ts` (`resolveImageUrl`). Order detail = green header + status pill + avatar/call + map-pin + item thumbnails + totals(subtotal/discount)/payment + download invoice. All operational/config/auth pages done too.
- Salesperson READ access: `AdminProductController` GET endpoints + `CustomerController` allow SALESPERSON (customers scoped to own via SalespersonScopeResolver); product create/update stay ADMIN-only; frontend hides mutation affordances for non-admins (`canManage`).
- Parity backend additions (no migration): order line `imageKey` (primary published product image, batch-loaded in `OrderService.getOrder`), `OrderResponse.discountAmount`, and `GET /api/admin/products/{id}/stats` (per-product current-month revenue + order count via `OrderProductSalesLookup`/`ProductSalesLookup` inversion). Backend 442 tests pass; admin builds.

## Consolidated Daily Report email (mail/report) — implemented
Richer daily admin email summarising the PREVIOUS day across the business, alongside the existing basic
`DailyDigestJob` (whose `@Scheduled` was REMOVED so only ONE email fires/day; its pure `buildDigestBody` stays).
New `com.shifa.oms.mail.report`: pure `ReportOrder` projection (status/total/cod/received/customer/mobile/
createdBy/salespersonName) + pure `DailyReport.build(day, List<ReportOrder>)` → model {Overall(orderCount,
totalSales, codAmount, prepaidReceived, deliveredCount, cancelledRejectedCount — all EXCLUDING REJECTED/
CANCELLED for sales), per-salesperson rows grouped by createdBy sorted by salesValue desc, status breakdown
(all statuses, lifecycle order), distinctCustomerCount by mobile, topCustomer}. `EmailModels.ConsolidatedReport`
wraps it; `EmailRenderer.renderConsolidatedReport` produces branded HTML (summary + salesperson table + status
table + customers block, reusing the existing brand header/footer/layout). `DailyReportService` (@Service,
@Transactional(readOnly=true)) `sendConsolidatedReportFor(LocalDate)` loads `OrderRepository.findByCreatedAtBetween`
[day 00:00, day+1 00:00), resolves salesperson names via `UserRepository.findAllById`, builds+renders+sends to
`app.mail.digest-to` via MailService (skips + logs when blank; never throws), returns `Result{date, orderCount,
totalSales, recipientConfigured}`. `DailyReportJob` `@Scheduled(cron="${REPORT_DIGEST_CRON:0 0 8 * * *}")` (default
now **8 AM**) gated by `app.mail.digest-enabled`/`REPORT_DIGEST_ENABLED` (default true; set false when an AWS Lambda
drives it). ADMIN trigger: `POST /api/admin/reports/daily-digest/run?date=` (`DailyReportController`
`@PreAuthorize hasRole('ADMIN')`, no SecurityConfig change) → the Lambda logs in then POSTs at 8 AM IST. Added
`MailProperties.digestEnabled`/`isDigestEnabled()` + `app.mail.digest-enabled` in `application.yml`. Pure builder
unit test `DailyReportTest` (6 cases). Backend **493 tests** pass (was 487). No migration, no frontend, MOCK mail
still logs.

## New-order UX: compact layout + state typeahead + order notes (V29) — implemented
Salesperson **New Order** form reworked to cut scrolling and add two fields:
- **Compact layout**: paired short fields share a row even on mobile (Phone|Lead source `col-6`, City `col-7`|Pincode `col-5`),
  `g-2` gutters + tighter section spacing; State moved to its own full-width row for the typeahead menu.
- **State typeahead** (`shared/state-typeahead.component.ts`, a `ControlValueAccessor` with a pure `fuzzyScore` fn —
  prefix/acronym/subsequence ranking; "mh"→Maharashtra, "up"→Uttar Pradesh, "tamilnadu"→Tamil Nadu). Options come from
  `GET /api/states` via `shared/states.service.ts` (`StatesService.activeNames`); free-typed values still allowed.
- **Order notes**: optional ≤1000-char textarea in a new "Notes" section just before save; sent as `CreateOrderRequest.notes`
  (and `LeadConvertRequest.notes` for convert). Shown on the order-detail drawer (`orders.component.html`).
- **Delivery-state master list** (new `com.shifa.oms.geo`): `DeliveryState`+`DeliveryStateRepository`+`DeliveryStateService`+
  `DeliveryStateController`. `GET /api/states` (any authenticated staff → active names for the picker); ADMIN CRUD under
  `/api/admin/states` (list-all/create/update/delete, audited as SETTINGS_UPDATED). Managed from a new **"Delivery states"**
  card on the Settings page (`settings.component.*`: add / enable-disable toggle / remove). Names unique case-insensitively.
- **Migration V29** (`V29__order_notes_and_delivery_states.sql`): adds `orders.notes VARCHAR(1000) NULL` + creates
  `delivery_states`(id, name UNIQUE, active, sort_order, timestamps) seeded once with 28 states + 8 UTs. Additive.
- Backend order DTOs threaded: `CreateOrderRequest.notes`, `OrderResponse.notes`, `OrderEntity.notes`, `LeadConvertRequest.notes`
  (LeadService.convert passes it through). Backend **493 tests** still pass; admin `build:admin` clean.

## Backend modules kept (`com.shifa.oms.*`)
auth, order (+`GET /api/orders/products` picker), statemachine, product, inventory, packing, courier,
label, invoice, reconciliation, reporting, finance, procurement, returns, crm, dashboard,
adminnotification, audit, settings, geo (delivery-state master list → `/api/states`, admin `/api/admin/states`),
search, agent, notification, mail, platform, common,
lead (Lead Management — Tasks 1–7 done, backend complete: `LeadStatus`/`LostReason` enums, `LeadEntity`/
`LeadStatusHistory`, `LeadRepository`/`LeadStatusHistoryRepository`, `LeadService` capture/edit/transition/
setFollowUp/convert/list/detail/pipelineCounts/dueFollowUps/reports; `LeadController` `/api/leads`
(`@PreAuthorize hasAnyRole('SALESPERSON','ADMIN')`, capture/list/pipeline/detail/status/follow-up/edit/
due/convert + `/reports/{by-source,conversion,pipeline,lost-reasons}`); `LeadConvertRequest`/`LeadReports`
DTOs; pure `LeadReportAggregator` (+`LeadReportRecord`); `FollowUpReminderJob` (@Scheduled `app.lead.reminder.cron`
default daily 09:00 → outbox `LEAD_FOLLOW_UP_DUE` + `StaffNotificationDispatcher.dispatchToUser`, idempotent via
`reminded_on`); dashboard summary extended (SALESPERSON leadPipeline+dueFollowUps, ADMIN leads/conversion).
Convert calls `OrderService.createSalespersonOrder` in same tx (rollback = no-op). Reuses `order.LeadSource`,
`SalespersonScopeResolver`, `AuditService`, `OutboxEventPublisher`; 409 via `common.IllegalLeadTransitionException`.
Frontend Task 8 **DONE** (Tasks 1–9 complete): Angular `leads/` feature — `LeadsService` (all `/api/leads`
endpoints incl. reports), `leads.model.ts` (Lead/LeadSummary/LeadStatus/LostReason/Create/Convert DTOs, pill
helpers), `LeadsComponent` (mobile-first status filter-tabs w/ counts + colored pills, tappable cards, capture
form w/ OTHER note + validation, FAB, detail drawer mirroring order-detail w/ status history + advance
NEW→CONTACTED→QUOTED + Mark Lost picker + set/clear follow-up + Convert), `DueFollowUpsComponent`
(`/leads/follow-ups`, overdue flagged). Convert flow = **New-Order prefill**: Convert navigates to
`/orders/new?leadId=N`; `NewOrderComponent` loads the lead, prefills+locks customer/source, and on save POSTs
`/api/leads/{id}/convert` (creates order + marks lead WON) instead of `/api/orders`. Routes `/leads` +
`/leads/follow-ups` guarded by `salespersonGuard` (ADMIN+SALESPERSON); shell hamburger gains a "Leads" group
(both roles); bottom 4 tabs unchanged. Salesperson dashboard gains a "My leads" widget (pipeline-by-stage
counts + due-follow-up badge) from the extended `dashboard.model.ts` (`SalespersonSummary.leadPipeline`/
`dueFollowUps`, `AdminSummary.leads`). Optional 8.5 spec `leads.component.spec.ts` (360px checks) type-compiles;
the vitest runner in this workspace can't bootstrap in isolation (pre-existing — existing specs fail identically).
Admin `build:admin` completes clean.
insights (Statistical Insights Engine — spec `statistical-insights-engine`; **backend Pass A+B DONE**). Pure
`insights/domain/*`: `InsightEngine` (7 families: SALES_ANOMALY/LOW_STOCK_REORDER/RTO_RISK/COURIER_SCORECARD/
RETURN_RATE_ANOMALY/COD_OUTSTANDING_BUILDUP/LEAD_SOURCE_CONVERSION) + enums (`InsightType/Scope/Severity`) +
`Insight`(natural key type/scope/scopeRefId/computedDate, GLOBAL uses `scopeRefId=0` sentinel) + `InsightThresholds`
+ projection records (`SalesWindow/ProductConsumption/CourierOutcome/OpenOrderRisk/ReturnStats/CodOutstanding/
LeadSourceConversion`) + `InsightInputs`; 8 jqwik property tests. `InsightEntity`+`InsightRepository` (V26 `insights`
table). Pass B: `InsightComputationService` (@Service dual-ctor Clock, binds `app.insights.*` via @Value →
`InsightThresholds`; `@Transactional computeForToday()` gathers read-only from order/stock/courier/returns/
receivable/lead repos into projections, runs engine, `deleteByComputedDate`+`saveAll` idempotent per date, notifies
WARNING/DANGER via outbox `INSIGHT_ALERT` + `StaffNotificationDispatcher.dispatchToRole(ADMIN)` de-duped by capturing
pre-existing notifiable natural keys before delete, audits `INSIGHTS_COMPUTED`; per-family gather in try/catch so one
failure is skipped). `InsightNightlyJob` (@Scheduled `app.insights.nightly.cron` default 02:00, best-effort).
`InsightService` read side + `InsightController` `/api/insights` (class `@PreAuthorize hasAnyRole('ADMIN','SALESPERSON')`:
`GET ""` list latest-date role-scoped w/ type/scope/severity/includeDismissed; `POST /{id}/dismiss` + `POST /recompute`
ADMIN-only) + DTOs `InsightResponse`/`RecomputeResponse`. SALESPERSON sees only own SALESPERSON-scope + own-order
RTO_RISK insights. Dashboard `RoleDashboardSummary.Admin` gains `Insights(countsBySeverity, List<InsightHeadline>)`
populated from latest computed date's non-dismissed insights (RoleDashboardService now injects `InsightRepository` in
BOTH ctors). Added finders `StockMovementRepository.findByMovementTypeAndCreatedAtBetween`/
`findTopByProductIdOrderByCreatedAtDescIdDesc`; `OutboxEvent.EVENT_INSIGHT_ALERT`; `AuditActions.INSIGHTS_COMPUTED`/
`ENTITY_INSIGHT`; `app.insights.*` in `application.yml`. Backend **487 tests** pass (was 482). **Frontend Pass C
DONE** (tasks 6–7): Angular `insights/` feature — `InsightsService` (`/api/insights` list/dismiss/recompute),
`insights.model.ts` (Insight/Severity/InsightType/InsightScope DTOs + `severityPillClass` Tabler badge mapping
INFO→bg-blue-lt/WARNING→bg-yellow-lt/DANGER→bg-red-lt, `severityGroupClass`, `insightTypeLabel`), `InsightsComponent`
(mobile-first severity filter-tabs w/ counts, cards grouped/colour-accented DANGER→WARNING→INFO w/ severity pill +
type label + title + detail + metric + computedDate + per-card Dismiss, prominent Recompute button w/ spinner/disabled,
empty state "No insights yet — run Recompute"). Route `/insights` guarded `adminOnlyGuard`; shell hamburger gains a
standalone ADMIN-only "Insights" link (icon ti-bulb) near Reports; bottom 4 tabs unchanged. Admin dashboard gains an
"Insights" KPI tile in the Fulfilment-queues row (counts by severity "N danger · M warning", routerLinks `/insights`)
from the extended `dashboard.model.ts` (`AdminSummary.insights: InsightsSummary{countsBySeverity, top: InsightHeadline[]}`).
Optional `insights.component.spec.ts` (360px checks) type-compiles. Admin `build:admin` completes clean.
**Removed in pivot:** account, review, payment, coupon, and public checkout/catalog/storefront-config controllers.

## Admin pages
dashboard, approval-queue, orders(+/new, +convert-from-lead via `?leadId=`), leads(+/follow-ups),
products, inventory, customers, returns, notifications,
audit, suppliers, purchase-orders, expenses, finance/pnl, packing, reconciliation, reports, settings, users.
Routes: `frontend/projects/admin/src/app/app.routes.ts`; nav: `shell/admin-shell.component.ts`.

## Database / migrations
- Flyway dir: `backend/src/main/resources/db/migration`. **Never edit an applied migration; add a new versioned one.**
- `V21` drops storefront tables; `V22` seeds a self-contained demo dataset (all profiles).
- `V23` adds `orders.lead_source`/`lead_source_note`/`customer_email` (+`ix_orders_lead_source`); `V24` adds `admin_notifications.recipient_role`/`recipient_user_id` (+`ix_admin_notifications_recipient`). Both additive/nullable (role-based-order-workflow).
- `V25` (lead-management) adds `leads` + `lead_status_history` tables (+ indexes `ix_leads_owner_status`/`_status`/`_follow_up`/`_source`/`_mobile`, `ix_lead_history_lead`); FKs → `users(id)`/`orders(id)`; `leads.reminded_on` DATE for follow-up de-dup. Additive, safe on seeded V22. Backend now **468 tests** pass (was 456; lead convert/API/reminders/reports/dashboard added).
- `V26` (statistical-insights-engine) adds the `insights` table (+ natural-key unique index).
- **Pluggable file storage** (`platform/storage`): `StorageService` has 3 impls chosen by `app.storage.provider` (env `STORAGE_PROVIDER`), each `@ConditionalOnProperty` so exactly one bean is active — `LOCAL` (filesystem, dev default, `matchIfMissing`), `DB` (`stored_files` table), `S3` (`S3StorageService`+`S3StorageConfig`, `@Primary`). S3 stores label PDFs under `labels/internal/` and payment screenshots under `payments/` in the bucket (`app.storage.s3.{bucket,region,prefix,access-key,secret-key,endpoint}`; EC2 instance role preferred, fail-fast if bucket blank). **Prod defaults to `S3`** (application-prod.yml). AWS SDK v2 `s3`+`apache-client` (BOM 2.29.29) added to pom. Root cause it fixes: `/opt/shifa` isn't writable by the `shifa` service user, so filesystem storage 500'd on approval (label PDF) and screenshot upload. Deploy: create bucket + IAM `s3:PutObject/GetObject`, set `STORAGE_PROVIDER=S3`/`STORAGE_S3_BUCKET` — see `docs/DEPLOYMENT-AWS.md` §23.
- `V29` (new-order UX) adds `orders.notes VARCHAR(1000) NULL` + creates `delivery_states` (name UNIQUE, active, sort_order),
  seeded once with 28 states + 8 UTs. Powers the New Order state typeahead (`GET /api/states`) + admin Settings management
  (`/api/admin/states`). Additive; safe on seeded data.
- `V28` (payment-screenshot storage) adds `stored_files` (id, `storage_key` UNIQUE, filename, content_type, byte_size, `content` LONGBLOB, created_at) — DB-backed binary store so payment screenshots are durable/backed-up records, not fragile server files. New `platform/storage/DatabaseStorageService` (`@Primary @ConditionalOnProperty app.storage.provider=DB`) + `StoredFileEntity`/`StoredFileRepository`; `LocalStorageService` now `@ConditionalOnProperty(...=LOCAL, matchIfMissing=true)` so exactly one bean is active. **Prod uses `app.storage.provider=DB`** (application-prod.yml; env `STORAGE_PROVIDER`). Also added `spring.servlet.multipart.max-file-size=10MB`/`max-request-size=12MB` (default 1MB was 500ing phone screenshots) + a `MaxUploadSizeExceededException`→413 handler in `GlobalExceptionHandler`.
- `V27__seed_test_data.sql` — **large NOW()-relative TEST/DEMO seed** layered additively on top of V22 (plain SQL, runs in ALL profiles, auto-applies on deploy/restart). Non-colliding explicit IDs: users 101–123, orders 1000–1119 (codes `SHR-5001`..`SHR-5120`), courier_companies 2–4, suppliers 10–14, purchase_orders 10–14 (`PO-0006`..`PO-0010`); child tables use AUTO_INCREMENT. Seeds 23 new users (20 salespersons `sales01`..`sales20` + `accountant2`/`packer2`/`admin2`) — **all logins share password `admin123`** (same bcrypt hash as V22 admin). Volumes: 120 orders across the full lifecycle (~56 customers, repeat buyers), 240 line_items, ~926 status_history rows (first row NULL→PENDING, last == order_status), 72 payments, 91 courier_records, 37 receivables (COD outstanding unsettled + a COURIER_LOST claim), 142 stock_movements (many SALE rows in the last 30 days for insights), 9 order_returns, 21 monthly expenses, 50 leads (+124 lead_status_history, due/overdue follow-ups, some WON→converted_order_id), 13 admin_notifications (role- & user-addressed), 18 audit_events. Bumps `invoice_sequence` (next_value 69) and `purchase_order_sequence` (next_value 11). Money math mirrors V22. **Guide: `docs/test-data-guide.html`** (full login list + per-role tour). Validated: V1..V27 apply 100% clean into a throwaway scratch DB (never touch `shifa_dashboard`).
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
