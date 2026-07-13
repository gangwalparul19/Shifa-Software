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
label, invoice, reconciliation, reporting, finance, procurement, returns, crm (+Customer 360), dashboard,
adminnotification, announcement (staff banners → `/api/announcements`, admin `/api/admin/announcements`),
push (Web Push VAPID, config-gated → `/api/notifications/push/*`),
audit, settings, geo (delivery-state master list → `/api/states`, admin `/api/admin/states`),
performance (Salesperson 360 → `/api/admin/salespeople/performance`, `/{id}/performance`, `/targets`),
analytics (retention + forecast → `/api/admin/analytics/{retention,forecast}`),
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
products, inventory, customers(+Customer 360 drawer), returns, notifications, announcements,
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
- `V34` (Customer 360, FEATURE-ROADMAP §1) creates `customer_tags` (mobile, tag, UNIQUE(mobile,tag)) + `customer_notes`
  (mobile, note, created_by/_name, created_at), keyed by `customer_mobile`. First persisted per-customer data.
- `V35` (announcements, §8.4) creates `staff_announcements` (message, severity, active, created_by/_name, timestamps).
- `V36` (web push, §8.3) creates `push_subscriptions` (user_id, endpoint UNIQUE, p256dh, auth_secret, created_at).
- `V37` (packing redesign) one-time data fix: clamps future `orders.created_at` back to now (no schema change).
- `V38` (analytics §6.1) creates `sales_targets` (per-salesperson monthly revenue target).
- **Highest migration is now V38.** V31/V32/V33 (staff profiles/photo/change-requests) sit between V29 and V34.
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

## Staff onboarding & salesperson ID verification (V31) — implemented
Previously only a salesperson's username + full name were stored. Added a full staff profile + ID-verification
workflow and an admin **Salespeople** directory, WITHOUT touching the tested `AdminUserService`/`AdminUserController`
(credential mgmt) — a separate service/controller owns profiles.
- **Migration V31** (`V31__staff_profiles_verification.sql`): adds to `users` → `date_of_birth`, `address`,
  `joined_on`, `id_proof_type`, `id_proof_number`, `id_proof_key`, `verification_status` NOT NULL DEFAULT 'PENDING',
  `verification_note`, `verified_at`, `verified_by`, + index `ix_users_role_verification`. Backfills ALL existing rows
  to `VERIFIED` (so seeded logins aren't flagged). Additive/nullable.
- Backend (`com.shifa.oms.auth`): enums `IdProofType` (AADHAAR/PAN/DRIVING_LICENSE/VOTER_ID/PASSPORT/OTHER) +
  `VerificationStatus` (PENDING/VERIFIED/REJECTED); `User` entity extended with the new fields (+getters/setters);
  `UserRepository.findByRoleOrderByCreatedAtDescIdDesc(Role)`; DTOs `StaffProfileResponse` (rich, `hasIdProof` boolean,
  never the doc bytes), `UpdateStaffProfileRequest` (fullName required; email/mobile/dob/address/joinedOn/idProofType/
  idProofNumber optional, validated), `VerifyStaffRequest` (status VERIFIED|REJECTED + optional note).
  `StaffProfileService` (deps UserRepository + **StorageService** + CurrentUserService): listSalespeople (role
  SALESPERSON), get, updateProfile, storeIdProof (stores under prefix `staff/id-proofs`, sets key, resets status→PENDING),
  loadIdProof, setVerification (blocks PENDING; verify requires an uploaded doc; sets verifiedAt/verifiedBy). `StaffController`
  `/api/admin/staff` (`@PreAuthorize hasRole('ADMIN')`): GET list, GET `/{id}`, PUT `/{id}`, POST `/{id}/id-proof` (multipart),
  GET `/{id}/id-proof` (inline blob like payment screenshot), POST `/{id}/verify`. Audits via new `AuditActions`
  STAFF_PROFILE_UPDATED/STAFF_ID_PROOF_UPLOADED/STAFF_VERIFIED/STAFF_VERIFICATION_REJECTED (ENTITY_USER).
- Frontend: `salespeople/` feature — `SalespeopleService` (all `/api/admin/staff` endpoints + blob id-proof),
  `SalespeopleComponent` (mobile-first cards, verification filter tabs w/ counts, right-side self-contained drawer:
  profile form + ID-proof upload/view/replace + Verify/Reject with note). Route `/salespeople` (`adminOnlyGuard`); shell
  Settings group gains a **Salespeople** link (icon ti-id-badge-2, adminOnly). Reuses PageHeaderComponent/StatePanelComponent/
  ToastService/ApiClient patterns.
- NOTE: no hard login/activation gate was added (keeps additive & test-safe — create()/activate() unchanged). Verification is
  an explicit, prominent, audited admin workflow (Verify disabled until a doc is uploaded). A hard "can't activate a
  salesperson until VERIFIED" gate is a possible follow-up (needs a small change to create/activate + test updates).
- Verified: `mvn clean test -Dtest=AdminUserServiceTest` green (9); full clean compile of 445 sources OK; admin `build:admin`
  bundle complete. Client-facing docs already list this as module #12 in `docs/Shifa-Pricing-Interactive.html` (₹5,000) and a
  feature in `docs/Shifa-Client-Presentation.html`.

## Staff two images (profile photo + govt ID) with server-side compression (V32) — implemented
Extended the staff feature to capture TWO images per salesperson and compress images before storage (S3/DB cost).
- **Migration V32** (`V32__staff_profile_image.sql`): adds `users.profile_image_key VARCHAR(255) NULL` (the govt-ID key
  `id_proof_key` already existed from V31). Additive.
- **`platform/storage/ImageCompressor`** (@Component, built-in `javax.imageio`, no new dep): `compress(filename,
  contentType, bytes, maxDimension, quality)` → scales longest edge to max, flattens alpha on white, re-encodes JPEG
  (quality 0.75), returns compressed Result ONLY if smaller; passes through non-images (PDF) and undecodable/failed cases.
- `StaffProfileService` now injects `ImageCompressor`: `storeIdProof` compresses image proofs (max 1600px; PDF pass-through);
  new `storeProfileImage` (max 600px) + `loadProfileImage`. Profile-image endpoints reject non-image content types.
- `StaffController`: `POST /{id}/profile-image` (multipart, image-only) + `GET /{id}/profile-image` (inline blob); refactored
  a shared `streamObject(...)` helper for id-proof + profile-image responses. New `AuditActions.STAFF_PROFILE_IMAGE_UPLOADED`.
  `StaffProfileResponse` gains `hasProfileImage`.
- Frontend `salespeople`: service `uploadProfileImage`/`profileImage`; component shows a **Profile photo** section (circular
  preview via authenticated blob→objectURL, upload/replace, image-only guard) above the Profile form, the Government ID
  section (upload/view/replace, unchanged), and list cards now show both a "govt ID" and a "photo" on-file indicator.
- Verified: backend `-DskipTests compile` BUILD SUCCESS (446 sources); admin `build:admin` bundle complete. Highest migration is now **V32**.

## Self-service "My Profile" with admin approval (V33) — implemented
Any signed-in staff member can view their profile and submit detail changes, but NOTHING on their `users` row changes
until an ADMIN approves — the edit is queued as a moderated request.
- **Migration V33** (`V33__staff_profile_change_requests.sql`): new `staff_profile_change_requests` table (proposed
  full_name/email/mobile/date_of_birth/address/id_proof_type/id_proof_number, request_note, status DEFAULT 'PENDING',
  review_note, requested_at, reviewed_at, reviewed_by; FK→users; indexes on (user_id,status) and status). At most one
  PENDING per user (a new submission replaces it).
- Backend (`com.shifa.oms.auth`): enum `ChangeRequestStatus` (PENDING/APPROVED/REJECTED); entity `ProfileChangeRequest`
  + `ProfileChangeRequestRepository` (findFirstByUserIdAndStatus / findByStatusOrderByRequestedAtAsc / countByStatus);
  `ProfileChangeRequestService` (getMyProfile, submitMyChangeRequest [upsert PENDING, does NOT touch user], listPending,
  pendingCount, review→on APPROVED applies proposed values to the User, on REJECTED leaves it unchanged; ReviewResult record).
  DTOs: `ProfileFields` (ofUser/ofRequest), `ProfileChangeRequestResponse` (current+proposed+meta), `MyProfileResponse`
  (profile+pending), `SubmitProfileChangeRequest`, `ReviewProfileChangeRequest`. New `MyProfileController` `/api/me/profile`
  (`hasAnyRole ADMIN/ACCOUNTANT/SALESPERSON/PACKING_USER`): GET (profile+pending), POST `/change-request`, GET `/photo`
  (own photo via StaffProfileService.loadProfileImage). Admin endpoints added to `StaffController`: GET
  `/api/admin/staff/change-requests`, POST `/api/admin/staff/change-requests/{id}/review`. New AuditActions
  STAFF_PROFILE_CHANGE_REQUESTED/APPROVED/REJECTED. StaffController ctor gained ProfileChangeRequestService (no test builds it).
- Frontend: `my-profile/` (MyProfileService `/api/me/profile`; `MyProfileComponent` route `/my-profile` guarded `staffGuard`
  — all roles; shows profile card + photo + pending "awaiting approval" diff banner + change-request form; photo/ID changes
  stay admin-only by design). `profile-approvals/` (`ProfileApprovalsComponent` route `/profile-approvals` `adminOnlyGuard`;
  lists pending requests with current→requested diff + Approve/Reject + note; uses SalespeopleService.changeRequests()/
  reviewChangeRequest()). Shared change-request TS types live in `salespeople.service.ts` (re-exported by my-profile.service
  to avoid circular defs). Shell nav: top-level **My Profile** link (all roles, icon ti-user-circle) + **Profile approvals**
  under Settings (adminOnly, icon ti-user-check).
- Admin still edits staff directly in the Salespeople directory (unmoderated); the approval flow is only for employee
  self-service. Verified: backend compile BUILD SUCCESS (456 sources), `AdminUserServiceTest` 9/9 green, admin `build:admin`
  bundle complete. Highest migration is now **V33**.

## Customer records & internal CRM — Customer 360 (FEATURE-ROADMAP §1) — implemented
Customers remain **derived data keyed by `customer_mobile`** (no customer master table). Kept the existing
`CustomerService`/`CustomerController` (list/detail `/api/admin/customers`) UNTOUCHED so their tests stay green
(the guard test's `StubCustomerService extends CustomerService` uses the 5-arg ctor). Added a SEPARATE
`crm.CustomerInsightService` + `crm.CustomerCrmController` (same `/api/admin/customers` base path, new sub-paths):
`GET /{mobile}/profile` (Customer 360), `GET /{mobile}/risk` (never 404s — new mobile = LOW/0), `POST /{mobile}/notes`,
`POST /{mobile}/tags`, `DELETE /{mobile}/tags/{tag}`. Class `@PreAuthorize hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON')`;
same `SalespersonScopeResolver` scoping (salesperson sees only their own-order customers; out-of-scope → 404).
- Pure `crm/domain/CustomerRiskCalculator` + `CustomerRiskLevel` (LOW/MEDIUM/HIGH): failed deliveries
  (CUSTOMER_REJECTED/DELIVERY_FAILED/RTO/COURIER_LOST) vs delivered (DELIVERED/COD_COLLECTED/CLOSED); HIGH when
  ≥2 failures AND failure-rate ≥0.4, MEDIUM when ≥1 failure, else LOW. Profile also has metrics (delivered/failed/
  in-flight/cancelled/successRate/outstanding), top products bought (excl. cancelled), status breakdown, tags, notes.
- **Migration V34** (`customer_tags`, `customer_notes`, keyed by mobile) — first persisted per-customer data. New audit
  verbs CUSTOMER_NOTE_ADDED/TAG_ADDED/TAG_REMOVED + ENTITY_CUSTOMER.
- Frontend `customers`: model/service extended (profile/risk/addNote/addTag/removeTag); drawer upgraded to a 360 view
  (risk banner, delivery mini-metrics, tags add/remove, products bought, notes timeline, order history). New Order form
  shows a **prepaid nudge** for MEDIUM/HIGH-risk mobiles (`customers.risk`). Item **1.3 (dup detect/merge) deferred** —
  true merge needs a customer master + rewriting order history. Verified: backend compiles, 21 tests pass
  (`CustomerServiceTest`+`EndpointRoleGuardIntegrationTest`), admin `build:admin` complete.

## Staff mobile & UX (FEATURE-ROADMAP §8) — implemented
- **8.1 PWA + offline orders**: `@angular/service-worker` wired (`projects/admin/ngsw-config.json` + `serviceWorker` in
  angular.json build options + `provideServiceWorker('ngsw-worker.js', {enabled: environment.production, registerWhenStable})`
  in `app.config.ts`), `public/manifest.webmanifest` + `public/icons/shifa-icon.svg`, PWA meta in `index.html`.
  `shared/pwa.service.ts` (online/offline signal, `beforeinstallprompt` capture, `SwUpdate` version-ready) → shell shows
  offline chip + Install button + update banner. `orders/offline-order-queue.service.ts` queues **COD (no-payment) orders**
  in localStorage when `!navigator.onLine`, auto-flushes to `POST /api/orders` on the `online` event/startup; New Order
  submit branches to enqueue when offline (paid orders + convert require connectivity), with a pending-sync banner.
- **8.2 camera scan**: `packing/camera-scanner.component.ts` uses native `BarcodeDetector` (code_128 + qr/ean/code_39),
  rear camera overlay, graceful fallback. `ScanComponent` gains a **Camera** button → `onCameraScanned(code)` sets the
  barcode field + `submit()` (reuses the normal scan flow).
- **8.3 web push (CONFIG-GATED)**: pom adds `nl.martijndwars:web-push:5.1.1` + `bcprov-jdk18on`. `push` module:
  `PushSubscriptionEntity`/`Repository` (V36 `push_subscriptions`), `WebPushService` (VAPID via `app.push.vapid.*`;
  **no-op unless keys set** — `pushService` stays null; best-effort/never-throws; payload shaped for ngsw auto-display
  `{notification:{title,body,data:{url}}}`), `PushSubscriptionService`, `PushController` (`/api/notifications/push/
  {public-key,subscribe,unsubscribe}`). Hooked best-effort into `StaffNotificationDispatcher.dispatchToRole/User` (ctor
  now takes `WebPushService` — updated 2 test call sites: `NotificationMatrixEnqueuePropertyTest`,
  `InsightComputationServiceTest`). Frontend `notifications/push-notifications.service.ts` (`SwPush` subscribe/unsubscribe
  + notificationClicks deep-link) + opt-in in the notification bell dropdown. `app.push.*` added to application.yml.
- **8.4 announcements**: `announcement` module — `Announcement` entity (V35 `staff_announcements`), repo, service (audit
  ANNOUNCEMENT_CREATED/UPDATED/DELETED + ENTITY_ANNOUNCEMENT), `StaffAnnouncementController` `GET /api/announcements`
  (isAuthenticated, active only) + `AdminAnnouncementController` `/api/admin/announcements` (ADMIN CRUD + `/{id}/active`).
  Frontend `announcements/` feature (route `/announcements` adminOnly, nav link under Settings) + shell renders active
  banners for all staff, dismissible per-user via localStorage.
- Verified: backend compiles (web-push + BC resolve), targeted tests pass (guard/notification/insights = 18), admin
  `build:admin` complete with `ngsw-worker.js`/`ngsw.json`/`manifest.webmanifest` emitted. **Highest migration now V36.**
- NOTE (pre-existing, out of scope): `POST /api/notifications/{id}/read` doesn't verify the notification belongs to the
  caller — flagged, not fixed.

## Packing page redesign (V37) — implemented
The `/packing` page (most-used floor screen) was rebuilt to match the app's design language:
- **KPI tiles** (awaiting pack / handover / dispatch counts), a **hero scan box** (keyboard scanner +
  phone camera button from §8.2), and the three work queues rendered as a **table** — columns Order ID /
  Customer / Price / Order date / Salesperson — with **clickable rows → `/orders?q={code}`**, per-row
  primary action (Mark packed / Handover / Dispatch) + Print label.
- Backend: new `packing/dto/PackingQueueRow` adds `salespersonName` + `orderDate` to each queue row;
  `PackingService.queue()` resolves salesperson names (via `UserRepository`) and maps to the new DTO;
  queues sorted **DESC by order date**. `PackingQueueResponse` now carries `PackingQueueRow` lists.
- Frontend `packing/scan.component.*` + `packing.model.ts` updated; focus uses `preventScroll:true` so
  opening the page no longer auto-scrolls to the bottom.
- **Migration V37** clamps any future-dated `orders.created_at` back to now (bad seed/test dates). No
  schema change. Verified: backend tests pass (`PackingServiceTest` updated), admin `build:admin` clean.

## Salesperson 360 + Analytics/insights/reporting §6 (V38) — implemented
Admin-facing performance tracking + analytics, so admins can monitor/compare salespeople daily.
- **`performance` module**: `SalespersonPerformanceService` (leaderboard = per-salesperson orders/
  revenue/conversion/delivery-success/target-progress; + per-person detail) + `SalespersonPerformanceController`
  (`GET /api/admin/salespeople/performance` leaderboard, `GET /{id}/performance` detail; ADMIN). **Sales
  targets (§6.1)**: `SalesTarget` entity + `SalesTargetService` (per-salesperson monthly revenue target),
  endpoints `GET/POST /api/admin/salespeople/targets` on the same controller. **Migration V38** creates
  `sales_targets`. Frontend: Salespeople page gains per-card KPIs + sort + a **performance drawer**;
  "Salespeople" promoted to a top-level nav entry (CRM group).
- **`analytics` module (§6.3 retention + §6.5 forecast)**: `RetentionService` (repeat-customer cohorts:
  new vs returning, repeat rate from `OrderRepository.customerOrderDates`), `ForecastService` (next-period
  revenue + product demand from trailing sales via `productDemandBetween`), `AnalyticsController`
  `GET /api/admin/analytics/{retention,forecast}` (ADMIN). Added `OrderRepository` queries
  `salespersonRevenueBetween`, `customerOrderDates`, `productDemandBetween`, `sumOutstandingCodActive`,
  `sumCodCollectedSince`.
- Frontend `analytics/` feature — tabs **Targets / Retention / Forecast**, route `/analytics`
  (`adminOnlyGuard`), nav under "Analytics & Reports". §6.4 configurable dashboard tiles = show/hide
  persisted in `localStorage`. §6.2 confirmed already covered by existing reporting.
- Verified: backend 25 analytics/performance tests pass; admin `build:admin` complete. Uses Clock
  dual-constructor pattern + `SalespersonScopeResolver` + `AuditService` (no duplication).

## Nav reorg + collapsible groups — implemented
`shell/admin-shell.component.ts` hamburger nav: groups are **collapsible (accordion), collapsed by
default**, reset on drawer close (`openGroups` signal + `isGroupOpen`/`toggleGroup` + chevron CSS).
Every link sits under a named group — only "Shifa Dashboard" is standalone. Groups: **CRM** (Leads,
Due follow-ups, Customers, Salespeople), **Analytics & Reports** (Reports [ADMIN+ACCOUNTANT], Analytics,
Insights), **Account & Settings** (My Profile, Settings, Users, …). Bottom 4-tab bar unchanged.
Also fixed the top appbar splitting into two rows: `.shifa-appbar__inner { flex-wrap: nowrap }`
(Bootstrap's `.navbar > .container-xl` inherits `flex-wrap: wrap`); actions/search made shrinkable.

## Global table-overflow fix — implemented
Wide tables (Returns, Orders, …) bled off-screen because Tabler `.card` is `display:flex; flex-direction:
column`, so `.card-body`/`.table-responsive` defaulted to `min-width:auto` and refused to shrink,
defeating `overflow-x:auto` and widening the whole page. Fix in `frontend/projects/admin/src/styles.css`:
`.card-body, .card > .table-responsive, .card-body > .table-responsive { min-width:0 }` +
`.table-responsive { min-width:0; max-width:100% }` so wide tables scroll **inside** their card instead
of stretching the layout. Global (covers every table). Frontend-only; admin `build:admin` clean.
