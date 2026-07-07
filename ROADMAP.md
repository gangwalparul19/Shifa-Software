# Shifa Herbal Remedies — Product Roadmap & Enhancement Backlog

A living catalogue of potential **new features**, **enhancements**, and **UI/UX improvements**
for the Shifa platform — split across the **Customer Storefront (PWA)** and the **Admin Panel**,
plus **cross-cutting** platform concerns.

> Status legend: 🟢 quick win · 🟡 medium effort · 🔴 large / multi-sprint
> Each item notes rough effort and the value it delivers. Nothing here is committed — it's a menu to pick from.

---

## 0. Where we are today (baseline)

Already shipped and working:

- **Storefront**: branded PWA, catalog + search, product detail, cart, wishlist, checkout → real order, order tracking, invoice download (plain / GST).
- **Admin**: Tabler light-theme dashboard (metrics, sales chart, live SSE feed, count-ups, welcome hero + sparkline), approval queue, orders, products, packing scan, reconciliation, reports (Excel/PDF/Vyapar), settings (GST toggle + company/GST config).
- **Backend**: order lifecycle state machine, salesperson order entry, admin approval, label + barcode PDFs, courier integration + tracking webhooks (mock), WhatsApp notifications (mock), COD/loss settlement, reconciliation ledger, nightly DB backups, per-order invoices (plain + GST tax invoice with CGST/SGST/IGST + HSN + ₹).
- **Integrations are mocked** (Courier API, WhatsApp Business API) behind swappable interfaces.

This document is about what comes *next*.

---

## 1. Customer Storefront

### 1.1 New Features

| Feature | Effort | Value |
|---|---|---|
| **Online payment gateway** (Razorpay/PayU/Cashfree) — pay now vs COD at checkout, capture payment, auto-mark Fully_Paid | 🔴 | Higher prepaid %, less COD risk |
| **Customer accounts & order history** — register/login, "My Orders", reorder, saved addresses | 🟡 | Repeat purchase, retention |
| **Product reviews & ratings** (with admin moderation) | 🟡 | Social proof, conversion |
| **Coupons / discount codes & offers** (percentage, flat, free shipping, min-cart) | 🟡 | Promotions, AOV | - Admin can add new coupons from the Settings page.
| **Product categories & collections** with category landing pages and filters | 🟡 | Discoverability |
| **Advanced filtering & sorting** (price range, category, availability, ailment/benefit tags) | 🟡 | Findability |
| **Guest → WhatsApp order handoff** — "Order on WhatsApp" button pre-filling cart to the sales team | 🟢 | Matches current sales flow |
| **Live chat / order-status bot** on the storefront (the requirement's "live agent") | 🔴 | Support, conversion |
| **Wishlist sharing & save-for-later** persistence across devices (needs accounts) | 🟢 | Engagement |

### 1.2 Enhancements

| Enhancement | Effort | Value |
|---|---|---|
| **SEO**: meta tags, Open Graph, sitemap.xml, structured data (Product/Offer schema), pretty URLs (slugs) | 🟡 | Discoverability |
| **Server-side rendering / prerender** (Angular SSR) for SEO + faster first paint | 🔴 | SEO + speed |
| **Cart persistence to backend** (currently client-side) so it survives devices | 🟡 | Continuity |
| **Checkout: address autofill via pincode** (city/state lookup) | 🟢 | Fewer errors, speed |
| **Order confirmation email/WhatsApp** to the customer immediately after checkout | 🟡 | Reassurance |
| **Inline field validation & better error surfacing** on checkout | 🟢 | Conversion |
| **Delivery estimate / shipping info** shown on product & checkout | 🟢 | Expectation-setting |

### 1.3 UI/UX Improvements

- **Polished home page**: hero carousel, featured collections, testimonials, trust badges (100% natural, FSSAI, secure), newsletter.
- **Sticky add-to-cart bar** on product pages (mobile), quantity stepper, quick-add from grid.
- **Cart drawer** (slide-in) instead of full page, with live subtotal and "you're ₹X away from free shipping".
- **Skeleton loaders & optimistic UI** for catalog/cart.
- **Micro-interactions**: add-to-cart fly-to-cart animation, wishlist heart animation, toast confirmations.
- **Empty states** with helpful CTAs (empty cart, no search results, empty wishlist).
- **Accessibility pass**: focus states, ARIA labels, color contrast, keyboard nav, `prefers-reduced-motion`.
- **PWA polish**: install prompt banner, offline fallback page, app icons/splash, push notifications for order updates.
- **Consistent design tokens**: spacing scale, typography ramp, button hierarchy, herbal-green + cream system.
- **Dark mode** (optional) for the storefront.

---

## 2. Admin Panel

### 2.1 New Features

| Feature | Effort | Value |
|---|---|---|
| **Inventory / stock management** — stock per product, auto-decrement on order, low-stock alerts, purchase/restock entries | 🔴 | Prevents overselling |
| **Full user management UI** — create/edit staff, assign roles, activate/deactivate, reset passwords (backend roles exist; no UI yet) | 🟡 | Operational control |
| **Coupons & promotions management** | 🟡 | Marketing |
| **Customer CRM view** — customer list, lifetime value, order history, repeat-buyer flags (ties to duplicate detection) | 🔴 | Retention, upsell |
| **Returns / refunds / RTO handling workflow** with reasons, restocking, refund tracking | 🔴 | Post-sale ops |
| **Bulk operations** — bulk approve, bulk label print, bulk status update, bulk product import (CSV) | 🟡 | Efficiency at scale |
| **Notifications center** — persistent notification history, read/unread, filters (beyond the live SSE feed) | 🟡 | Never miss events |
| **Audit log / activity trail** — who changed what/when (status history exists per order; make it global & searchable) | 🟡 | Accountability |
| **Multi-courier support & rules** — choose courier per order/zone, rate comparison, serviceability by pincode | 🔴 | Cost/coverage |
| **Purchase orders / supplier management** | 🔴 | Supply chain |
| **Expense & P&L view** (revenue vs courier costs vs claims) | 🟡 | Finance visibility |
| **Company logo & branding on invoices/labels** (upload in settings) | 🟢 | Professional docs |
| **Scheduled report emails** (daily sales digest to admin) | 🟡 | Passive insight |

### 2.2 Enhancements

| Enhancement | Effort | Value |
|---|---|---|
| **Server-side pagination, sorting, column filters** on Orders/Products/Reconciliation tables | 🟡 | Scale to 1000s of rows |
| **Global search** (orders/products/customers) from the top bar | 🟡 | Speed |
| **Saved views / filters** on the orders table (e.g. "COD pending", "RTO this week") | 🟡 | Workflow |
| **Dashboard date-range compare & drill-down** (click a metric → filtered list) | 🟡 | Analytics depth |
| **Richer charts** (ApexCharts): revenue trend, orders by status donut, courier performance, state heat/leaderboard | 🟡 | Insight |
| **HSN entry during salesperson order entry** and per-product tax class | 🟢 | GST accuracy |
| **Settings expansion**: invoice numbering series/prefix, terms & conditions text, bank details on invoice, multiple GST slabs | 🟡 | Compliance/flexibility |
| **Optimistic updates + toasts** consistently across all mutating actions | 🟢 | Snappy feel |
| **Real-time table updates** via SSE (new orders appear without refresh) | 🟡 | Live ops |

### 2.3 UI/UX Improvements

- **Icon subsetting** for Tabler icons (currently ships full webfont) to cut bundle size. 🟢
- **Breadcrumbs + consistent page headers** across all pages. 🟢
- **Density toggle** (comfortable/compact tables). 🟢
- **Better empty/loading/error states** everywhere (some pages still basic). 🟢
- **Confirmation modals** for destructive/irreversible actions with clear consequences. 🟢
- **Mobile/tablet polish** for warehouse/packing use on the floor (large touch targets). 🟡
- **Consistent status color language** shared with the storefront tracking.
- **Accessibility**: focus management in drawers/modals, ARIA roles, contrast audit.
- **Onboarding/empty-first-run** guidance (seed data hints, "add your first product").

---

## 3. Cross-Cutting / Platform

### 3.1 Integrations
- **Analytics**: Google Analytics / Meta Pixel on the storefront; funnel & conversion tracking.

### 3.2 Reliability, Security & Performance
- **Automated tests**: E2E (Playwright/Cypress) for both apps; expand integration tests; contract tests for courier/WhatsApp.
- **Rate limiting & brute-force protection** on auth and public endpoints.
- **Refresh-token rotation, password reset, optional 2FA** for admin.
- **Input hardening & audit** (SQL/CSV injection, file-upload validation for screenshots).
- **Observability**: structured logging, request tracing, health/metrics endpoints, error alerting.
- **Backups**: restore drill/runbook, off-site retention policy, verify integrity (backup job exists).
- **Performance**: DB indexing review at scale, API pagination, image CDN/optimization, bundle-size budgets.
- **Caching**: catalog/read caching; ETag/Cache-Control for storefront.

> **Deployment & hosting** (OCI Always Free VM, Nginx, redeploy workflow, HTTPS, etc.) is documented
> separately in **`DEPLOYMENT.md`** — this roadmap stays focused on product features & enhancements.

---

## 4. Suggested Prioritization (a possible path)

**Phase 1 — Commercial readiness (make it sellable/operable)**
1. Real Courier API + real WhatsApp Business API (swap mocks). 🔴
2. Online payment gateway on the storefront. 🔴
3. Inventory/stock management + low-stock alerts. 🔴

**Phase 2 — Growth & retention**
5. Customer accounts + order history + reorder. 🟡
6. Coupons/offers + reviews & ratings. 🟡
7. Categories, filtering, SEO (+ SSR). 🟡🔴
8. Storefront home-page polish + PWA push notifications. 🟡

**Phase 3 — Operational depth**
9. User management UI, returns/RTO workflow, bulk operations. 🟡🔴
10. CRM/customer view + LTV, richer dashboard analytics. 🟡
11. Notifications center + global search + server-side table paging. 🟡

**Phase 4 — Polish & scale**
12. Dark mode (both apps), command palette, accessibility audit, icon subsetting.
13. E2E test suite, observability, rate limiting/2FA, caching/performance.

---

## 5. Quick Wins (low effort, visible impact)

- Company **logo on invoices/labels** (upload in Settings). 🟢
- **HSN during order entry** + per-product tax class. 🟢
- **Pincode → city/state autofill** at checkout. 🟢
- **Cart drawer** + fly-to-cart animation on the storefront. 🟢
- **Confirmation modals** + consistent toasts across the admin. 🟢
- **Tabler icon subsetting** to shrink the admin bundle. 🟢
- **Order confirmation WhatsApp/email** on checkout. 🟢
- **Better empty/loading/error states** across both apps. 🟢

---

*This roadmap is a menu, not a commitment. Pick items and I can turn any of them into a proper spec (requirements → design → tasks) and implement them.*
