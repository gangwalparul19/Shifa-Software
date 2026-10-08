# Shifa OMS — Architecture Reference

> **Single source of truth for how this application is built.** Keep it updated in every commit that
> changes structure, modules, migrations, lifecycle, roles, integrations, or deploy. When you add a
> module/endpoint/migration/role, update the matching section here in the same change.
> Companion docs: `README.md` (quick facts + run commands), project memory steering, `ENHANCEMENT.md`
> (roadmap), `DEPLOYMENT.md` (canonical AWS deploy).

---

## 1. What this is

Dashboard-only **Order Management System** for Shifa Herbal Remedies. The public storefront is on
**Shopify**; this app is the internal admin + salesperson operations platform (order entry, approval,
packing, courier handoff, payments/GST accounting, CRM, reporting). No public-facing checkout here.

## 2. Stack & topology

| Layer | Technology |
|---|---|
| Backend | Java 21 lang level (runs on Java 25 runtime), Spring Boot 3.3.5 **modular monolith** |
| DB | MySQL 8 + Flyway (`ddl-auto: validate`), JVM & DB pinned `Asia/Kolkata` |
| Security | Spring Security + JWT (15-min access, 7-day refresh) + method-level RBAC |
| Frontend | Angular 21 workspace — `admin` app (port 4300) + `core`/`ui` libs, Tabler theme, brand green `#1F5D3F`, ApexCharts |
| Deploy | AWS EC2 (Nginx + systemd), MySQL on-instance, S3 file storage. Live: `https://shifa.weblithic.online` (`15.252.230.73`) |
| Integrations | QuikShipX courier (LIVE), Shopify order webhook (LIVE, toggleable), WhatsApp click-to-chat (mock), email (SMTP) |

**Run commands (Windows/cmd, explicit paths):**
- Backend (8080): `mvn -f "backend/pom.xml" -DskipTests spring-boot:run`
- Admin UI (4300): `npm --prefix frontend run start:admin`
- Backend tests: `mvn -f "backend/pom.xml" test` (full suite 858 tests; exclude `-Dtest=!WhatsappTemplateEncodingIT` for DB-less runs)
- Admin build: `npm --prefix frontend run build:admin`

## 3. Backend module map (`com.shifa.oms.*`)

| Package | Responsibility |
|---|---|
| `auth` | Users, JWT, roles, login rate-limit, staff profiles/verification, self-service profile change requests, team assignment |
| `order` | Order entity + lifecycle, order entry (salesperson/admin/store), pricing, discounts, export, channel summary, suggestions |
| `statemachine` | `OrderStatus`, `OrderStatusStateMachine` (legal transitions), `TransitionAuthority` (role authorization) |
| `product` | Catalogue, price bands (min/sale/MRP), GST/HSN, images, CSV import, per-product stats |
| `inventory` | Stock movements, reservation, adjustments |
| `packing` | Packing queues, scan & move, labels, RTO scan, multi-pack, print-label queue |
| `courier` | Courier companies, assignment, shipping-label render, tracking URL templates |
| `quikshipx` | QuikShipX client, payload factory, order shipment, drainer (async create→confirm→allot), tracking poller, retry |
| `shopify` | Order-import webhook (HMAC-verified), import service, admin recover/stuck, sync-enabled toggle |
| `label` | Internal shipping-label PDF (4-up A4), bundled logo fallback |
| `invoice` | GST tax-invoice PDF (per-line discount/GST%, per-rate breakup), amount-in-words |
| `reconciliation` | Receivables, COD remittance import (CSV/Excel), COD aging/SLA, unsettled lists |
| `reporting` | Report catalogue (sales/orders/money/operations), CSV/Excel export, module reports |
| `finance` | P&L |
| `ledger` | Double-entry ledger, chart of accounts, vouchers, day book, auto-posting (approval/PO/expense/payment/delivery) |
| `gst` | GST engine (CGST/SGST/IGST + export 18% IGST), CA dashboard, GSTR-1 filing, reconciliation |
| `procurement` | Suppliers, purchase orders |
| `returns` | Order returns, RTO auto-return + credit note |
| `crm` | Customer 360 (derived by mobile), risk, tags, notes |
| `dashboard` | Role-shaped summaries, channel dashboard, teams overview, live metrics (SSE) |
| `performance` | Salesperson 360, team performance, sales targets |
| `analytics` | Retention cohorts, revenue/demand forecast |
| `insights` | Statistical insight engine (7 families), nightly job |
| `adminnotification` | Persistent in-app notifications (role/user addressed) |
| `announcement` | Staff banners |
| `push` | Web Push (VAPID, config-gated) |
| `audit` | Audit events |
| `settings` | App settings (seller GST identity, logo, GST pricing mode, auto-approval, Shopify sync) |
| `geo` | Delivery-state master list |
| `mail` | Email outbox + drainer, daily/weekly consolidated report |
| `search` | Global command-palette search |
| `platform` | Pluggable storage (LOCAL/DB/S3), image compressor |
| `common` | Shared DTOs, exceptions, spreadsheet parser, Jackson time config |
| `lead` | Lead management (capture→convert), follow-up reminders, reports |
| `adminexception` | Read-only exception center (approvals/payments/failures/claims/insights) |

## 4. Order lifecycle (state machine)

**`OrderStatus`** values:
`PENDING_ADMIN_APPROVAL → APPROVED → LABEL_GENERATED → PACKED → HANDED_TO_DELIVERY → COURIER_ASSIGNED
→ DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → {DELIVERED → (CLOSED | COD_COLLECTED)}`
Exception/terminal branches: `REJECTED`, `PAYMENT_REJECTED`, `CANCELLED`, `CUSTOMER_REJECTED`,
`DELIVERY_FAILED`, `RTO`, `REDISPATCH`.

Rework edges (NOT terminal): `REJECTED → PENDING_ADMIN_APPROVAL`, `PAYMENT_REJECTED → PENDING_ADMIN_APPROVAL`
(fix & resubmit). Retry-delivery edges: `DELIVERY_FAILED`/`CUSTOMER_REJECTED → OUT_FOR_DELIVERY` and `→ RTO`.
`RTO`/`REDISPATCH` are terminal (give-up path raises a GST credit note / claim receivable — do NOT reopen).

**Central transition path (every status change goes through this):**
`order/OrderWorkflowService.applyTransition(order, target, Actor)` =
1. **authorize** — `statemachine/TransitionAuthority` (403 if the actor's role can't make this edge)
2. **legality** — `OrderStatusStateMachine` (409 if the edge isn't legal from current status)
3. apply new status + append exactly one `status_history` row
4. audit
5. `notification/NotificationMatrix` fan-out (WhatsApp per-step to customer; email only on APPROVED/DISPATCHED/DELIVERED; in-app to roles/creator) via outbox.

`Actor` = a human role or `SYSTEM` (courier/webhook/auto-approval). Auto-approval & Shopify import use
`Actor.user("...", Role.ADMIN, "...")` because PENDING→APPROVED is authorized for ADMIN/ACCOUNTANT only (not SYSTEM).

**Status groups (6, salesperson-facing)**: `PENDING_APPROVAL, PROCESSING, SHIPPED, DELIVERED, FAILED_RETURNED,
CANCELLED` (`order/OrderStatusGroup` with lenient `from(String)` aliasing old keys). Mirrored on the frontend
`orders/order-status-groups.ts`.

## 5. Roles, auth & scoping

**Roles** (`auth/Role`): `ADMIN, ACCOUNTANT, SALESPERSON, TEAM_LEAD, PACKING_USER, PAYMENT_VERIFIER, CA`.

**Scoping** (`order/SalespersonScopeResolver`):
- `creatorScope(principal)` → SALESPERSON = `[ownId]`; TEAM_LEAD = `[ownId + team member ids]` (self-inclusive,
  so a team lead's own punched orders show); ADMIN/ACCOUNTANT/CA = empty Optional (unscoped/global).
- `teamMemberScope(principal)` → team members ONLY (excludes self) — used for team-performance rollups.
- **Present-but-empty list = scoped to NOTHING** (a lead with no team sees no orders, never all).

**Auth safety:** frontend never attaches a saved JWT to public `/api/auth/{login,refresh,register}`.
`authInterceptor` scopes the bearer to the configured API host only (`isApiRequest`). On 401 it does one
silent refresh + retry; a failed refresh clears the session and routes to `/login`. Login is rate-limited
(5 failures/15-min lockout per username|IP). Prod boot fails fast if `JWT_SECRET` is blank/default/<32 chars.

**Display names:** the JWT carries a `name` claim; UI greets with `auth.displayName()` (full name, falls back
to username) — never raw username (salespeople log in with their mobile number).

## 6. Migrations (Flyway, `backend/src/main/resources/db/migration`)

**Rule: never edit an applied migration; always add a new versioned one. Highest is V79.**

| Range | Theme |
|---|---|
| V1–V20 | Initial schema, settings/HSN, product GST, invoice numbering + line tax, admin notifications, audit, returns, suppliers/POs, expenses, stock |
| V21–V22 | Drop storefront tables; seed self-contained demo dataset |
| V23–V30 | Lead source/customer email, notification addressing, leads, insights, large NOW()-relative test seed (V27), stored_files, order notes + delivery states |
| V31–V38 | Staff profiles/verification/photo/change-requests, customer CRM tags/notes, announcements, push subs, future-date fix, sales targets |
| V39–V48 | Alt mobile, handover name, multi-pack, payment-verifier fields, price list seed, GST demo reset (V51 DESTRUCTIVE), buyer GSTIN, UQC, **ledger core/seed** (V54/55), GST returns filing, QuikShipX shipments (V57), tracking-url fixes, delivery method, RTO reason, redispatch rename (V48) |
| V49–V50 | Product catalog: minimum_rate, wt_ml; order discount_type/value; seed 30 products |
| V60–V69 | Delivery method, RTO reason, vehicle number, return credit-note value, **multi payment screenshots** (V65), reject reason (V66), country (V67), **shopify_order_id + unique index** (V68), shipment label-printed flag (V69) |
| V70–V76 | Shopify sync toggle, **dashboard aggregation indexes** (V71), payment-screenshot content hash (V72), auto-approval settings (V73), shipment cancelled, QuikShipX failure reason, courier_record tracking_url (V76) |
| V77–V79 | **Customer tracking token** (V77 `orders.tracking_token` unique + backfill), **return refund method** (V78 `order_returns.refund_method`), **product cost price** (V79 `products.cost_price`) for channel-margin analytics |

Start against an **empty** `shifa_dashboard` (V22 uses explicit IDs). V27 test seed + V51 GST demo reset
(DESTRUCTIVE — wipes orders, reseeds ~200) run in ALL profiles.

## 7. Frontend (Angular admin app)

- **Routes/guards:** `frontend/projects/admin/src/app/app.routes.ts`. Guards: `staffGuard` (all 7 roles),
  `adminOnlyGuard`, `salespersonGuard`, `orderEntryGuard` (ADMIN/SALESPERSON/TEAM_LEAD), `packingGuard`,
  `accountantGuard`, `reportsGuard`, `paymentVerifierGuard`, `teamLeadGuard`, `adminOrCaGuard`.
  **Every nav link must carry `roles`/`adminOnly` matching its route guard** (ungated = visible to all = bug).
- **Shell:** `shell/admin-shell.component.ts`. Desktop ≥992px = persistent left sidebar (collapsible accordion
  groups); mobile = hamburger drawer + role-aware 4-tab bottom bar. SSE connected for all staff roles.
- **Role landings:** PAYMENT_VERIFIER→`/payments`, CA→`/ca/gst`; others→role-shaped `/dashboard`.
- **Pages:** dashboard, approval-queue, orders(+/new, /:id/edit, convert, reorder, resubmit), leads(+follow-ups),
  products, inventory, customers (360), returns, notifications, announcements, audit, suppliers, purchase-orders,
  expenses, finance/pnl, packing (+pick-list, +RTO), reconciliation, reports, settings, users, salespeople,
  my-profile, profile-approvals, team (assign), team-performance, teams-overview, leaderboard, analytics,
  insights, exceptions, payments, ca-gst (dashboard/reconciliation/filing), ledger (CoA/voucher/day-book/
  trial-balance/statement/balance-sheet/P&L/cash-flow), shopify-sync, whatsapp-templates.
- **Shared conventions:** `| istDate` pipe (never `| date`), `InrPipe` for money, `admin-pagination` +
  `readPageSize`/`writePageSize`, `admin-state-panel` (skeleton+retry), canonical `.badge.tone-*` status pills,
  `STATUS_LABEL_OVERRIDES` humanize map, `shared/role-label.ts`.

## 8. Integrations

- **QuikShipX (LIVE):** async pipeline via outbox — publish create → confirm → allot tracking id. Drainer
  (15s) + self-healing re-drive (`scheduledRedrive`, 10-min) re-queues transiently-FAILED events; genuinely
  permanent failures stay FAILED for manual review. Per-order **retry** endpoint `POST /api/orders/{id}/quikshipx/retry`
  (ADMIN). Retry ladder 12 attempts / 2-min backoff. Tracking poller (5-min) drives status from COURIER_ASSIGNED on.
  Payload amount check is float-equality — per-line discount apportionment + consolidated fallback.
- **Shopify (LIVE, toggleable):** `POST /api/webhooks/shopify/orders` (permitAll, HMAC-SHA256 base64 verified).
  Imports → `source=SHOPIFY`, auto-approves (fully-prepaid path), idempotent via `shopify_order_id`. Admin
  toggle `app_settings.shopify_sync_enabled` (default OFF); recover/stuck endpoints under `/api/admin/shopify`.
- **WhatsApp:** server-managed templates (`whatsapp_templates`, V44-47) with placeholders rendered client-side;
  click-to-chat `wa.me` links. **Use ≤3-byte BMP symbols** (astral emoji mangle on WhatsApp Desktop handoff).
- **Mail:** SMTP via outbox + `EmailOutboxDrainer`. Daily consolidated report (8 AM) + optional weekly (default OFF).

## 9. Deploy & ops

- **Canonical:** `DEPLOYMENT.md`. One command: `deploy\push-to-aws.ps1 -KeyPath "<pem>" -Ip 15.252.230.73`
  (⚠️ default IP in the script is stale `13.234.22.207` — **always pass `-Ip 15.252.230.73`**).
  `-SkipBuild` reuses the prebuilt JAR + admin bundle. `aws-apply.sh` on the box: mysqldump backup → swap JAR
  → publish admin → restart `shifa-oms` → reload nginx.
- **Verify a deploy via SSH (console/log output is unreliable):** `systemctl is-active shifa-oms`,
  journalctl for Flyway "applied Vnn" / "Started Application", `curl` site=200 + `/api/states`=401 + served
  `main-*.js` hash. The local deploy log can hang on "Restarting backend..." even after the remote finished.
- **Storage:** prod `STORAGE_PROVIDER=S3` (bucket `shifa-oms-files`, IAM instance role). `/etc/shifa/shifa.env`
  (mode 600, root) holds secrets + `JAVA_OPTS` (must be quoted). `/actuator/health` = 200 (mail health indicator
  disabled — it pings SMTP and would make health 503).
- **DB creds for admin SQL:** `sudo bash -c 'set -a; . /etc/shifa/shifa.env; set +a; MYSQL_PWD="$DB_PASSWORD" mysql -u"$DB_USERNAME" "$DB_NAME"'`.

## 10. Testing patterns

- Full suite 858 tests. `ApplicationContextLoadsTest` (`@SpringBootTest`, H2 `smoketest` profile) boots the full
  context DB-less → catches missing-`@Autowired`/broken DI before deploy.
- **Java 25 can't mock concrete classes (Mockito/ByteBuddy):** mock repository *interfaces*, use real instances or
  recording subclasses for concrete services/entities. Dual-constructor services (one takes `Clock` for tests)
  **must `@Autowired` the primary ctor** or the context fails to start.
- Pure-domain logic (pricing, GST engine, status groups, transition table) covered by jqwik property tests.

## 11. Known gotchas (bit us before)

- Shell exit codes spuriously return **-1** even on success → judge by printed output.
- IDE Eclipse JDT language server locks `backend\target\classes` → `mvn clean` fails. Build to a relocated
  `-Dproject.build.directory="C:\shifa-build\<dir>"` or an out-of-workspace source copy.
- Fat JAR is **~105-110 MB**; the plain jar (~2.8 MB) is written first then repackaged — confirm size before deploy
  (a thin JAR deploy = "no main manifest attribute" crash-loop).
- Don't rely on tool `cwd` (PowerShell `cd` prefix breaks cmd) — use `-f`/`--prefix` explicit paths.
- `control_pwsh_process start` reuses/replays a stale terminal — verify by fresh server state, not console echo.

---

## 12. AUDIT FINDINGS (2026-10-03 full-app scan)

Status key: 🔴 open-high · 🟡 open-medium · 🟢 verified-good/no-action · ✅ fixed.

### 12.1 Performance (N+1 / unbounded scans)

| # | Location | Sev | Issue | Fix |
|---|---|---|---|---|
| 1 | `reconciliation/ReconciliationService.unsettledCod()` | ✅ fixed | per-row `findById`/`findByOrderId`/`findById(user)` ≈ 3 queries/row; scanned receivables twice | now batch-loads via `findAllById`/`findByOrderIdIn` into a `RefData` record; single receivables scan |
| 2 | `ReconciliationService.toResponse()` (per-row from list/paged) | ✅ fixed | ≈4N queries | all list/paged paths preload orders/couriers/users per page via `loadRefData()` |
| 3 | `gst/GstAccountingService` toGstOrders/orders/hasLineRate/hasLineHsn | ✅ fixed | lazy `getLineItems()` N+1; `report()`→`dashboard()` re-queried window 2-3× | `findByCreatedAtBetweenWithLineItems` (JOIN FETCH DISTINCT); window loaded once via shared `buildReport` |
| 4 | `GstAccountingService.dashboard()` | ✅ fixed | `purchaseOrderRepository.findAll()` + `orderReturnRepository.findAll()` filtered in Java | windowed SQL SUM finders `sumTotalCreatedBetween` / `sumRefundByStatusCreatedBetween` |
| 5 | `dashboard/TeamsOverviewService.overview()/rowFor()` | ✅ fixed | per-team `leaderboardFor` re-ran full-table aggregate + `findByRole` | `leaderboard()` computed once, indexed by id, sliced per team in-memory |
| 6 | `dashboard/DashboardMetricsService` liveStats/activityCards | ✅ fixed | `findAll()` on the SSE hot path | `liveStatsBetween` SQL COUNT+SUM; `activityCards` uses `statusCounts()` GROUP BY. (`metrics()` `loadRecords()` kept `findAll()` by design — not the SSE tick) |
| 7 | `order/ChannelSummaryService.loadWindow()` | ✅ fixed | `findAll()` for open-ended window | one-sided windows now push a bounded `findByCreatedAtBetween` with wide sentinels; only all-time loads all |
| 8 | `insights/InsightComputationService` | 🟡 | 3× `findAll()` + per-product N+1 (nightly 02:00 — low urgency) | windowed finders + batch product load |
| 9 | `gst/Gstr1ReturnService.buildCreditNotes()` + `productRepository.findAll()` | 🟡 | lazy N+1 + full product scan | JOIN FETCH + `findAllById` |
| 10 | `reporting/ModuleReportService` (4 sites) | 🟡 | `findAll()` + Java window filter | windowed finders |

**Index gaps:** 🟢 `orders.shopify_order_id` unique index **confirmed present** (V68 `uq_orders_shopify_order_id`).
🟡 `outbox(event_type,status,next_attempt_at)` composite missing (only `(status,next_attempt_at)`) — the re-drive
scans by event_type prefix. No `@BatchSize` anywhere; all `OrderEntity` associations are LAZY (good).

**Already optimal (do not re-touch):** `AdminOrderService.listOrders`, `OrderService.getOrder`,
`PackingService.queue`, `ReturnService.list`, `DailyReportService`, the role dashboards (GROUP BY aggregates),
all outbox drainers (scoped `findDue`).

### 12.2 DTO integrity

**Verdict: no active bug or boot-crash today. Both high items are LATENT (future-edit) risks.**

| # | Location | Sev | Risk |
|---|---|---|---|
| R1 | `order/dto/OrderResponse` | ✅ guarded | now **52 positional components** + 4 withers. `OrderResponseWitherRoundTripTest` builds a fully-distinct instance, calls each wither, and reflects over `getRecordComponents()` asserting every non-changed component is preserved — catches a positional swap, and a future field add breaks the canonical ctor call so withers must be updated in lockstep. |
| R2 | `reporting/domain/OrderReportRecord` | ✅ fixed | telescoping 16/17-arg ctors **deleted**; one canonical 18-arg ctor remains; all call sites updated. |
| R3 | `ChannelSummaryResponse` / `ChannelDashboardResponse.ChannelSplit` | 🟢 | `store` channel correctly threaded. |

**Verified good:** all dual-ctor @Services `@Autowired` the primary ctor (guarded by `ApplicationContextLoadsTest`);
enums serialize UPPER_SNAKE `name()`; `JacksonTimeConfig` only customizes `LocalDateTime` (+05:30).
**Watch:** `StoreOrderRequest`/`CreateOrderRequest`/`UpdateOrderRequest` are parallel — a field added to one
won't propagate to the others (highest future-bug spot). **Not yet audited:** `AdminOrderResponse`,
`StaffProfileResponse`, `gst/dto/Gstr1ReturnResponse`.

---

## 13. Keep-this-updated checklist (run on every commit)

- [ ] New/removed **module** → §3 table.
- [ ] New **migration** → §6 (bump "highest is Vxx").
- [ ] New **endpoint/route/guard** or role → §5, §7.
- [ ] **Order lifecycle / state-machine / authority** change → §4.
- [ ] **Integration** behavior change (QuikShipX/Shopify/mail/WhatsApp) → §8.
- [ ] **Deploy/ops** change (scripts, env, nginx, systemd) → §9, and `DEPLOYMENT.md`.
- [ ] Audit item **fixed** → flip its row in §12 to ✅ with the commit note.
- [ ] New **gotcha** learned → §11.
