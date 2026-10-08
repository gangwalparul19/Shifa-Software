# Sales Enablement & AI-Based Insights — Proposal

> Forward-looking enhancement plan for the Shifa OMS (dashboard-only). Two focus areas the client
> asked for — **(A) helping the salesperson capture and work data properly**, and **(B) AI-based
> reporting so the admin gets rich insights** — plus **(C) other high-value options**. Every idea is
> mapped to the data/stack we already have, stays inside the **OCI Always Free** budget, and follows
> our existing **swappable/mock-first integration** philosophy (courier / WhatsApp / email / payments
> are all behind interfaces with MOCK modes — AI should be the same).

This is a proposal, not a spec. Once we agree on scope, each phase becomes its own Kiro spec.

---

## 0. What we have to build on (data foundation)

The insights are only as good as the data. Today the schema already captures a lot:

- **orders** — `lead_source` (+ note), `created_by` (salesperson), `customer_mobile`, `customer_email`,
  `total/amount_received/remaining/cod`, `discount_amount`, `coupon_code`, `customer_user_id`, status, timestamps.
- **status_history** — every lifecycle transition with `actor`, `source`, `changed_at` (great for cycle-time analysis).
- **line_items** — product, qty, rate, line total, HSN, GST (basket / product analytics).
- **receivables** — COD/claim amounts + settled state (cash-flow analytics).
- **expenses** + **P&L** — cost side for margin analytics.
- **stock_movements** — RESTOCK/SALE/RETURN/ADJUSTMENT ledger (demand + inventory analytics).
- **order_returns** — reason + status (quality / dissatisfaction signals).
- **courier_records** — AWB, courier, last status (delivery-performance analytics).
- **audit_events**, **admin_notifications** — operational trail.
- Existing reports: `ORDERS_BY_LEAD_SOURCE`, `ORDERS_BY_STATUS`, `ORDERS_BY_SALESPERSON`,
  `DELIVERY_OUTCOME`, daily/monthly sales, P&L.

**Gaps to close first** (cheap, high leverage — these unlock everything downstream):
1. **Lead lifecycle** — today a lead only becomes visible once it's an order. Add a lightweight
   **Lead** entity (status: NEW → CONTACTED → QUOTED → WON/LOST, source, owner, next-follow-up date,
   lost-reason). This is the single biggest enabler for both sales productivity and funnel analytics.
2. **Follow-up / reminder timestamps** and a **lost-reason taxonomy** (price, out-of-stock, no-response…).
3. **Delivery-attempt details** (attempt count, failure reason) on courier updates — powers RTO prediction.
4. **Structured return-reason categories** (already free text — add an enum) for quality analytics.
5. **Per-salesperson targets** (monthly value/units) to measure attainment.
6. Ensure consistent **timestamps + actor** on every mutation (mostly there via status_history/audit).

---

## A. Salesperson enablement (capture data properly + sell more)

Goal: make the salesperson faster and more accurate on mobile, and turn scattered WhatsApp/IG/FB/Google/
offline leads into a clean pipeline.

### A1. Lead capture & pipeline (highest priority)
- A **Leads** screen + entity: capture a lead the moment it arrives (name, mobile, source, note),
  before it's a full order. Stages NEW → CONTACTED → QUOTED → WON (converts to order) / LOST (reason).
- **Convert-to-order** button pre-fills the New Order form from the lead (no re-typing).
- **My pipeline** view: leads grouped by stage with counts and value — the salesperson's daily worklist.
- Powers the funnel/conversion analytics in section B.

### A2. Faster, cleaner order entry
- **Customer 360 at entry**: when a mobile is typed, show past orders, outstanding COD, last products,
  preferred items — we already have duplicate detection (`/api/orders/duplicate-check`); extend it to a
  mini customer card.
- **Repeat / quick reorder**: "reorder last" or one-tap add of a customer's frequent items.
- **Live stock + price in the picker**: show on-hand and low-stock warning while adding lines (we have
  `/api/orders/products` + stock) so they don't promise out-of-stock items.
- **Draft orders**: save an incomplete order and resume later (field reality — interruptions).
- **Voice / quick note** and **SKU/barcode search** in the product picker for speed.

### A3. Follow-ups & reminders
- **Next-follow-up date** on leads/orders + a **"Today's follow-ups"** list and in-app reminders
  (reuse the notification/outbox infra). Optional WhatsApp reminder to the customer.
- **Payment-collection reminders** for COD/partially-paid orders assigned to the salesperson.

### A4. Salesperson self-dashboard (motivation + clarity)
- Personal KPIs: leads in pipeline, conversion %, orders this month, sales value vs **target**,
  approval-pending count, COD outstanding on their orders. (We already scope data by `created_by`.)
- **Leaderboard / streaks** (light gamification) — optional, drives engagement.

### A5. Field-friendliness
- **PWA offline capture**: queue new orders/leads offline and sync when back online (the storefront had
  a service worker; bring a scoped version to the admin for order/lead entry).
- **WhatsApp deep-link intake**: a `wa.me`/click-to-chat flow that seeds a lead with the customer's number.

---

## B. AI-based reporting & admin insights

Design principle: **AI behind a swappable interface** with a MOCK/rule-based default (no external calls,
free-tier safe) and an optional HTTP provider (e.g. OpenAI / AWS Bedrock / Azure OpenAI / a small local
model). Heavy analytics run as a **nightly batch** that writes a compact `insights` table the dashboard
reads instantly; the LLM layer only *summarizes/explains* pre-computed numbers (cheap, safe, deterministic-ish).

### B1. Natural-language querying ("ask your data")
- An **"Ask" box** on the admin dashboard: "top 5 products last month", "which lead source converts best",
  "COD outstanding by salesperson". 
- Safe implementation: **NL → a fixed metric catalog** (not raw SQL) — the LLM maps the question to one of
  our existing report types + parameters (date range, group-by). This avoids arbitrary-SQL risk and works
  even with a small model. (A guardrailed NL→SQL over read-only views is a later option.)

### B2. Automated narrative insights & digests
- **Daily/weekly digest** (we already have a mail digest job): an LLM turns the day's numbers into a short
  plain-English summary — "Sales ₹X (+12% WoW), driven by Immunity; delivery-failure rate up to 8% on
  courier Y; 3 products near stock-out." Falls back to a templated summary when AI is in MOCK mode.
- **Anomaly detection** (statistical, no LLM needed): flag sales dips/spikes, delivery-failure spikes,
  unusual return rates, COD-outstanding buildup — surfaced as admin notifications.

### B3. Predictive analytics (start simple/statistical, upgrade to ML later)
- **Demand forecast & reorder suggestions**: per-product moving-average / seasonality from `stock_movements`
  + line-item history → suggested reorder qty and stock-out ETA (feeds Purchase Orders).
- **RTO / delivery-failure risk score**: from historical `courier_records` + `status_history` + address
  region + COD amount — highlight risky orders before dispatch so admin can confirm.
- **Customer RFM segmentation & churn risk**: Recency/Frequency/Monetary from orders → segments
  (VIP / regular / at-risk / dormant) to guide salesperson follow-ups.
- **Lead-source ROI**: conversion % and revenue per channel (WhatsApp/IG/FB/Google/offline) — where to
  spend effort. (We already have `ORDERS_BY_LEAD_SOURCE`; add conversion once leads exist.)

### B4. Recommendation & optimization
- **Cross-sell / product-affinity** ("customers who bought X also bought Y") from basket data — surface as
  order-entry suggestions for salespeople.
- **Best-time / next-best-action** hints for follow-ups (from response patterns) — later phase.

### B5. Quality & sentiment
- **Return-reason clustering / sentiment** on return notes → recurring quality issues per product.

### AI architecture (keeps free-tier + mock-first)
```
[nightly batch job] --reads--> orders/line_items/status_history/stock_movements/...
      | computes metrics + anomaly flags + forecasts (pure Java/stat, no external calls)
      v
   insights table  <-- dashboard reads instantly (fast, offline-safe)
      ^
      | optional narrative/NL layer
[AiInsightProvider]  MOCK (templated, default)  |  HTTP (OpenAI/Bedrock/…, opt-in via env)
```
- New module `com.shifa.oms.insights` with an `AiInsightProvider` interface (mirrors
  `PaymentGateway`/courier/WhatsApp MOCK-vs-HTTP). Default MODE=MOCK → templated summaries + statistical
  models only, **zero external cost**. Switch to HTTP with an API key when the client wants LLM narratives.
- Keep all customer PII out of external prompts (send aggregates only); document data-privacy stance.

---

## C. Other high-value options

- **Courier performance scorecards** (on-time %, RTO %, avg transit) to negotiate/allocate couriers.
- **COD reconciliation automation** + ageing report; auto-flag overdue receivables.
- **SLA / cycle-time tracking** from `status_history` (approval→pack→dispatch→deliver) with bottleneck alerts.
- **Scheduled/exported reports** (email a weekly Excel/PDF) — extend the existing exporters + digest job.
- **Real WhatsApp Business API** (currently mock) for true customer messaging + inbound lead capture.
- **GST e-invoice / e-way bill** integration (India compliance) — build on the existing GST invoice.
- **Multi-warehouse / location stock** if operations grow.
- **Deeper role dashboards** (packer throughput, accountant cash position) — extend `/api/dashboard/summary`.
- **Accessibility (WCAG) & i18n (EN/HI)** pass for the admin (i18n groundwork existed in the old storefront).
- **Audit-driven security insights** (unusual admin actions) from `audit_events`.

---

## D. Suggested phasing (impact vs effort)

**Phase 1 — Foundation & quick wins (mostly our stack, no AI/external cost)**
- Lead entity + pipeline + convert-to-order (A1); follow-ups/reminders (A3); salesperson self-dashboard
  with targets (A4); customer-360 at entry + live stock (A2).
- Statistical **anomaly flags** + **reorder suggestions** + **RTO risk** as batch-computed insights (B2/B3),
  surfaced on the admin dashboard. Lead-source conversion + courier scorecards (B3/C).

**Phase 2 — AI narrative + NL querying (swappable provider, MOCK default)**
- `insights` module + `AiInsightProvider` (MOCK); AI daily/weekly narrative digest; NL "Ask" box over the
  metric catalog (B1/B2). Opt-in HTTP provider for real LLM.

**Phase 3 — Advanced ML & recommendations**
- Demand forecasting with seasonality, RFM/churn segmentation, cross-sell affinity, return-reason clustering
  (B3/B4/B5). Consider a small managed ML/LLM only if the client wants it (keep the free-tier default intact).

**Cross-cutting for every phase:** capture clean data (section 0), keep PII out of external calls, keep a
MOCK mode so demos and the free tier never depend on paid AI, and add each as its own Kiro spec.

---

## E. Data-capture checklist (do these first — they enable the AI)

- [ ] Lead entity (status, source, owner, next-follow-up, lost-reason).
- [ ] Follow-up dates + reminders on leads/orders.
- [ ] Structured return-reason + delivery-failure-reason enums.
- [ ] Per-salesperson monthly targets.
- [ ] Delivery-attempt count/reason on courier updates.
- [ ] Confirm consistent timestamps + actor on all mutations (largely done via status_history/audit).

Once Phase-1 data is flowing cleanly, the statistical insights are immediately useful, and the AI
narrative/NL layer (Phase 2) becomes a thin, low-risk, swappable add-on.
