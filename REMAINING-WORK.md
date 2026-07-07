# Shifa — Remaining Work (grouped for execution)

A focused list of everything from `ROADMAP.md` that is **not yet implemented**, grouped into
work-sets by usage so we can knock them out set by set. Items already shipped are summarized at the
bottom for context.

> Effort: 🟢 quick win · 🟡 medium · 🔴 large / multi-part
> Status of each item here = **NOT done** unless marked *(partial)*.

---

## Recommended execution order

1. **Set A — Admin Analytics & Realtime** (high demo value, self-contained) 🟡
2. **Set B — Admin Ops Depth** (CRM, returns/RTO, notifications, audit) 🟡🔴
3. **Set C — Supply Chain & Finance** (couriers, POs, P&L, digests) 🔴
4. **Set D — Storefront finishing** (dark mode, push, SSR) 🟡🔴
5. **Set E — Real Integrations** (courier/WhatsApp/email/payment go-live, analytics) 🔴
6. **Set F — Reliability, Security & Performance** (hardening before real launch) 🟡

Sets A–D are feature work (great for the client demo). Sets E–F are "go-live / production hardening".

---

## Set A — Admin Analytics & Realtime (2.2)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| A1 | **Richer charts (ApexCharts)** — revenue trend line, orders-by-status donut, courier performance, top states/products leaderboard on the dashboard | 🟡 | Add a charting lib; feed from existing dashboard metrics + reports endpoints. |
| A2 | **Dashboard date-range compare & drill-down** — pick a date window, compare vs previous period, click a metric → open the filtered Orders list | 🟡 | Reuse the new paginated `/api/admin/orders` filters for drill-down. |
| A3 | **Real-time table updates via SSE** — new orders / status changes appear in Orders & Approval without refresh | 🟡 | The admin SSE stream already exists (dashboard live feed); subscribe the tables to it. |
| A4 | **Saved views / filters** on the Orders table (e.g. "COD pending", "RTO this week") | 🟡 | Persist filter presets (localStorage first; per-user server-side later). |

## Set B — Admin Operations Depth (2.1)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| B1 | **Customer CRM view** — customer list, lifetime value, order history, repeat-buyer flags | 🔴 | Aggregate from orders + accounts; new backend endpoints + admin page. Ties into duplicate detection. |
| B2 | **Returns / refunds / RTO workflow** — initiate return, capture reason, restock, track refund; beyond the current `RTO` status label | 🔴 | New state transitions + entities; wire to inventory restock + settlement/refund tracking. |
| B3 | **Notifications center** — persistent notification history, read/unread, filters (beyond the live SSE toast feed) | 🟡 | New table for notifications; the outbox events can feed it. |
| B4 | **Audit log / activity trail** — global, searchable "who changed what/when" (order status history exists per-order; make it global) | 🟡 | Central audit table + interceptor/aspect; admin viewer with filters. |
| B5 | **Bulk product CSV import** | 🟡 | Upload + validate + preview + commit; complements existing bulk order ops. |

## Set C — Supply Chain & Finance (2.1)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| C1 | **Multi-courier support & rules** — choose courier per order/zone, rate comparison, serviceability by pincode | 🔴 | Courier registry + rule engine; builds on the existing (mock) courier abstraction. |
| ~~C2~~ | ~~**Purchase orders / supplier management**~~ | ✅ done | Supplier + PO entities (V19), receiving flow feeds inventory restock; admin Suppliers + Purchase Orders pages under the **Procurement** nav group. |
| ~~C3~~ | ~~**Expense & P&L view** — revenue vs courier costs vs claims~~ | ✅ done | Expenses (V20) + `/api/admin/finance/pnl`; admin Expenses + Profit & Loss pages under the **Finance** nav group. |
| ~~C4~~ | ~~**Scheduled report emails** — daily sales digest to admin~~ | ✅ done | `DailyDigestJob` (@Scheduled, cron `REPORT_DIGEST_CRON`, default 06:30) emails the previous-day sales digest to `REPORT_DIGEST_TO` via the new MailService. |

## Set D — Storefront finishing (1.2 / 1.3)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| D1 | **PWA push notifications** for order status updates | 🔴 | Web Push + service worker subscription + backend push sender; needs VAPID keys. |
| D2 | **SSR / prerender (Angular SSR)** for SEO + faster first paint | 🔴 | **Deployment tradeoff**: full SSR needs a Node process on the VM (competes with MySQL+JVM on the 1 GB micro). Recommend static **prerender** of stable pages, or defer until a bigger host. Meta/OG/JSON-LD/sitemap already shipped, so core SEO is covered. |

## Set E — Real Integrations (swap mocks / go-live) (3.1)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| E1 | **Real Courier API** integration (swap the mock behind the existing interface) | 🔴 | Interface + webhooks already exist in mock form. |
| E2 | **Real WhatsApp Business API** (Meta Cloud) — swap `MockWhatsAppClient` | 🔴 | Outbox drainer + template registry already built; add live HTTP client + credentials. WhatsApp number set to +91 9302590767. |
| ~~E3~~ | ~~**Email infrastructure** (SMTP/provider)~~ | ✅ done | Swappable `MailService` — `MockMailService` (default, logs) / `SmtpMailService` (Gmail-ready STARTTLS), selected by `MAIL_MODE`. Powers C4 digests; ready for order-confirmation email + password reset (F2). Set `MAIL_MODE=SMTP` + `MAIL_USERNAME`/`MAIL_PASSWORD` (Gmail app password) to go live. |
| E4 | **Payment gateway go-live** — real Razorpay keys (sandbox already works behind a swappable interface) | 🟡 | Config/keys + webhook verification for production. |
| E5 | **Analytics** — Google Analytics / Meta Pixel on the storefront + funnel/conversion tracking | 🟢 | Script + consent + event hooks. |

## Set F — Reliability, Security & Performance (3.2)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| F1 | **Rate limiting & brute-force protection** on auth and public endpoints | 🟡 | Bucket4j / filter; lock on repeated failed logins. |
| F2 | **Refresh-token rotation, self-service password reset, optional 2FA** | 🟡 | Admin can reset *other* users' passwords already; self-service reset needs email (E3). |
| F3 | **Input hardening & upload validation audit** (SQL/CSV injection, file-upload checks) | 🟡 | Review all inputs + screenshot/logo/import uploads. |
| F4 | **Observability** — structured logging, request tracing, health/metrics endpoints, error alerting | 🟡 | Actuator + JSON logs + an alert sink. |
| F5 | **Backups** — restore drill/runbook, off-site retention, integrity verification | 🟡 | Nightly backup job exists; add a documented restore + off-site copy. |
| F6 | **Performance** — DB indexing review at scale, image CDN/optimization, catalog/read caching, ETag/Cache-Control | 🟡 | Some indexes added (V14); do a holistic pass. |
| F7 | **Automated tests** — E2E (Playwright/Cypress) for both apps; **fix the admin Vitest unit runner** (currently fails to init in this env); contract tests for courier/WhatsApp | 🟡 | Frontend unit-test runner needs repair; backend has ~380 tests. |

---

## Already shipped (for context — do NOT redo)

- **Storefront 1.1** — online payments (sandbox), customer accounts + order history, reviews & ratings + moderation, coupons/offers, categories/collections + filters/sort, stock awareness, related products, WhatsApp order handoff, live order-status chat, EN/HI i18n, **wishlist + wishlist sharing**.
- **Storefront 1.2** — SEO (title/meta/OpenGraph/Twitter, Product/Offer JSON-LD, sitemap.xml, robots.txt, slug URLs), cart persistence to backend, pincode → city/state autofill, order confirmation (WhatsApp), inline checkout validation, delivery estimate. *(SSR deferred — see D3.)*
- **Storefront 1.3** — global toast service, cart drawer + free-shipping progress, mobile sticky add-to-cart, fly-to-cart + wishlist-heart micro-interactions, PWA install banner + offline page, skip-link + focus/reduced-motion a11y, design tokens; **full responsive overhaul** (header/navbar fit, compact cart-icon buttons, 2/3/4-col grids). *(Dark mode + push pending — D1/D2.)*
- **Admin 2.1 (partial)** — **staff user management UI**, **inventory** (auto-decrement, low-stock alerts, stock-movement ledger + admin page), coupons & reviews management, **bulk operations** (approve / mark-packed / merged-label PDF), company logo on invoices/labels, **New Order (salesperson/admin) entry with per-line price override**.
- **Admin 2.2 (partial)** — server-side pagination/sort/filter on Orders/Products/Reconciliation, global search, **HSN + per-product tax class** (order-line snapshot), **settings expansion** (invoice numbering, T&C, bank details, GST slabs), consistent toasts.
- **Admin 2.3** — page headers + breadcrumbs, table density toggle, confirmation modals, empty/loading/error states, shared status-badge color language, packing/warehouse mobile polish, accessibility pass, **grouped navigation (Dashboard / Orders / Catalog / Reports / Settings) + Shifa-green/gold theme**.
- **Platform** — order state machine, admin approval, label + barcode PDFs, packing scan, courier + tracking webhooks (mock), COD/loss settlement, reconciliation, reporting (Excel/PDF/Vyapar), nightly backups, transactional outbox, per-order invoices (plain + GST). Migrations at **V15**.
- **Admin Set B** — Customer CRM, persistent Notifications center + bell, global Audit log, Returns/refunds/RTO workflow, bulk product CSV import (migrations V16–V18).
- **Admin Set C (partial)** — **Suppliers + Purchase Orders** (Procurement group, receiving feeds inventory restock, V19), **Expenses + Profit & Loss** (Finance group, V20), **scheduled daily sales-digest email** (C4). Only **C1 multi-courier rules** remains in Set C.
- **Email infrastructure (E3)** — swappable MailService (MOCK default / SMTP Gmail-ready); `MAIL_*` + `REPORT_DIGEST_*` env vars documented in `deploy/shifa.env.example`.
- **Storefront dark mode** (roadmap 1.3) — semantic theme tokens + `ThemeService` (localStorage + system default) + header/menu toggle. *(Set D still has D1 push + D2 SSR pending.)*
- **Deployment** — single OCI Always-Free VM (Nginx + Spring Boot + MySQL), documented in `DEPLOYMENT.md` with a 3-command redeploy workflow.
