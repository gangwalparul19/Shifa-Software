# Shifa OMS — Feature Roadmap & Enhancement Ideas (Admin Dashboard Only)

> A working reference of features we could add on top of what's already shipped.
> **Scope reminder: this is a dashboard-only Order Management System.** There is NO customer-facing
> storefront, customer login, cart, or online checkout — **Shopify owns all of that.** Every feature
> below is for internal staff (ADMIN / SALESPERSON / PACKING_USER / ACCOUNTANT) or salesperson-captured
> orders. Nothing here is committed yet — it's a menu to prioritise.

---

## Scope boundary — what is OUT (Shopify owns it)

To avoid re-suggesting storefront features, these are explicitly **out of scope**:
- Customer-facing website, product catalogue browsing, cart, wishlist
- Customer self-service login / account area
- Online checkout & storefront payment collection
- Customer-submitted online reviews / ratings
- Storefront coupons / promo banners

Everything below is **internal dashboard + salesperson order entry only**.

---

## How to read this

Each item has: **What** · **Why** (value to Shifa ops) · **Effort** (S=1–2d, M=3–5d, L=1–2w, XL=2w+)
· **Touches** (modules) · **Migration?** (DB change needed).
Priority: 🔥 High impact / quick win · ⭐ Strong value · 💡 Nice to have · 🧪 Experimental

---

## What we already have (baseline — don't rebuild)

Order lifecycle workflow (role-based), packing + label printing, courier/tracking (mock),
COD reconciliation, finance/P&L + expenses, suppliers + purchase orders, returns, lead management
(internal CRM), statistical insights engine, daily report email, notifications (WhatsApp/email/in-app
outbox — currently mock), staff profiles + ID verification + self-service profile approval, pluggable
file storage (S3/DB/local), reports (by lead source/status/salesperson, delivery outcome), settings,
delivery-state master list.

---

## 1. Customer records & internal CRM (staff-facing only)

### 1.1 Customer 360 profile page 🔥
- **What**: A single internal view of a customer — all their orders, lifetime value, COD reliability (delivered vs rejected/RTO), products bought, last contact, linked leads, staff notes timeline.
- **Why**: Salespeople repeat-sell to the same customers over phone/WhatsApp. Today customer data is scattered across orders; one screen makes upsell and follow-up obvious.
- **Effort**: M · **Touches**: `crm`, `order`, new aggregation endpoint, frontend `customers` page · **Migration?**: No (optional `customer_notes` table later)

### 1.2 Customer RTO / COD-risk score 🔥
- **What**: Auto-flag customers with a history of rejections/RTO/COD-refusals. Show a badge during salesperson order entry ("⚠️ 2 past RTOs — consider prepaid").
- **Why**: COD losses are the #1 margin killer. Nudging risky customers to prepaid at entry time saves shipping + RTO cost. Insights already has an RTO_RISK family to build on.
- **Effort**: M · **Touches**: `insights`, `order` entry form, `crm` · **Migration?**: No (derive from order history)

### 1.3 Duplicate customer detection & merge 💡
- **What**: Detect the same customer by mobile/email across orders and offer a merge.
- **Why**: Repeat buyers get re-created each time → inflated counts and broken lifetime value.
- **Effort**: M · **Touches**: `crm`, `order` · **Migration?**: Maybe (a customer master table if we formalise it)

### 1.4 Customer segments & tags 💡
- **What**: Staff tag customers (VIP, wholesale, repeat, city) and filter/report by segment. Feeds the ops-driven WhatsApp nudges in §5.
- **Why**: Targeted reorder outreach without exporting to spreadsheets.
- **Effort**: M · **Migration?**: Yes (`customer_tags`)

---

## 2. Salesperson order entry & reorder

### 2.1 Quick reorder from a past order 🔥
- **What**: A "Reorder" button on any past order pre-fills a new salesperson order with the same items/customer.
- **Why**: Herbal remedies are repeat-consumption. One tap for the salesperson on a repeat buyer — big daily time-saver.
- **Effort**: S · **Touches**: `order`, frontend orders/new · **Migration?**: No

### 2.2 Draft / resume order 🔥
- **What**: A salesperson can save a half-finished order as a draft and resume it later from a "Drafts" list. (Internal draft — not a storefront cart.)
- **Why**: Phone/WhatsApp orders get interrupted mid-entry; drafts prevent lost sales.
- **Effort**: S–M · **Touches**: `order` (draft status), frontend New Order · **Migration?**: No (reuse status) or small

### 2.3 Ops-driven reorder reminder tasks ⭐
- **What**: For consumable products with a known supply cycle (e.g. 30 days), auto-create a **follow-up task for the salesperson** N days after delivery, plus an optional WhatsApp nudge sent by ops.
- **Why**: Turns repeat consumption into recurring revenue. This is staff-driven (a task in the dashboard), not a customer self-service subscription.
- **Effort**: M · **Touches**: `lead`/`notification` (reuse `FollowUpReminderJob` pattern), `product` (cycle days), `order` · **Migration?**: Yes (`products.reorder_cycle_days`, `reorder_reminders`)

### 2.4 Product bundles / combos ⭐
- **What**: Admin defines a bundle (e.g. "Immunity Kit" = 3 products at a combo price); salesperson adds it as one line and inventory decrements the components.
- **Why**: Higher average order value and easier upsell during a call. (Applies to salesperson-entered orders; keep in sync with Shopify if the same SKUs sell there — see §9.)
- **Effort**: M · **Touches**: `product`, `order`, `inventory` · **Migration?**: Yes (`product_bundles`, `bundle_items`)

### 2.5 Rule-based discounts at order entry 💡
- **What**: Reusable, auditable discount rules for salesperson orders (bulk qty, first-time buyer) instead of ad-hoc manual discount amounts.
- **Why**: Consistent, controlled discounting with an audit trail. (Internal entry-time only — not storefront promo codes.)
- **Effort**: M · **Touches**: `order`, `finance`, `settings`, `audit` · **Migration?**: Yes

---

## 3. Inventory & procurement

### 3.1 Batch / expiry tracking 🔥
- **What**: Track stock by batch number + expiry date; first-expiry-first-out picking guidance; alert on near-expiry stock.
- **Why**: **Herbal/consumable products have shelf lives and legal expiry-labelling needs.** Close to mandatory for the domain and a strong compliance story.
- **Effort**: L · **Touches**: `inventory`, `product`, `packing`, `insights` (near-expiry alert family) · **Migration?**: Yes (`stock_batches`, batch ref on movements/line items)

### 3.2 Auto reorder suggestions → draft PO ⭐
- **What**: When stock < reorder threshold, auto-draft a purchase order to the usual supplier (insights already flags LOW_STOCK_REORDER).
- **Why**: Turns an alert into a one-click action; prevents stockouts on best-sellers.
- **Effort**: M · **Touches**: `insights`, `procurement`, `inventory` · **Migration?**: Maybe (supplier-product mapping)

### 3.3 Stock adjustment / stocktake workflow ⭐
- **What**: A formal count-and-adjust flow with reason codes (damage, expiry, theft) and an audit trail.
- **Why**: Reconciles physical vs system stock and feeds accurate P&L (shrinkage).
- **Effort**: M · **Touches**: `inventory`, `audit`, `finance` · **Migration?**: Maybe (adjustment reasons)

### 3.4 Supplier price history & comparison 💡
- **What**: Track cost per product per supplier over time; show the best supplier.
- **Why**: Procurement negotiation leverage.
- **Effort**: M · **Migration?**: Yes

### 3.5 Multi-location / warehouse stock 💡
- **What**: Track stock across more than one location; allocate orders per location.
- **Why**: Only if Shifa expands to a second stocking point.
- **Effort**: L · **Migration?**: Yes

---

## 4. Finance & accounting

### 4.1 GST-compliant reporting (GSTR-ready exports) 🔥
- **What**: HSN-wise sales summary, tax collected, and GSTR-1/3B-friendly CSV/Excel exports for the accountant/CA.
- **Why**: **Indian statutory requirement.** HSN + line tax are already stored (V2/V15) — this just surfaces them for filing. Huge value for the ACCOUNTANT role.
- **Effort**: M · **Touches**: `finance`, `reporting`, `invoice` · **Migration?**: No (data already captured)

### 4.2 Accounts receivable ageing 🔥
- **What**: COD outstanding grouped by age bucket (0–7 / 8–15 / 16–30 / 30+ days), by courier and customer, with a chase list.
- **Why**: COD cash stuck with couriers is real working capital. Insights flags COD_OUTSTANDING_BUILDUP; this is the operational drill-down.
- **Effort**: M · **Touches**: `finance`, `reconciliation`, `reporting` · **Migration?**: No

### 4.3 Profit per order / per product margin ⭐
- **What**: Landed cost (from PO) vs sale price → true margin per order and per product, net of shipping + COD fees + RTO.
- **Why**: Shows which products actually make money after logistics.
- **Effort**: M–L · **Touches**: `finance`, `procurement` (cost), `order` · **Migration?**: Maybe (cost snapshot on line item)

### 4.4 Payment collection for salesperson orders (payment links) ⭐
- **What**: For manually-entered (phone/WhatsApp) orders, generate a Razorpay/PhonePe payment link the salesperson sends; auto-reconcile the order on payment. (This is for OMS-captured orders — Shopify still handles storefront payments.)
- **Why**: Converts risky COD to prepaid on manual orders — the biggest lever against RTO loss. Currently payment on manual orders is mock/screenshot.
- **Effort**: L · **Touches**: `finance`, `notification`, new `payment` module, webhook endpoint · **Migration?**: Yes (payment intents/links)

### 4.5 Expense receipts + categories + recurring 💡
- **What**: Attach receipt images to expenses, categorise them, and auto-create recurring monthly expenses (rent, salaries).
- **Why**: Cleaner P&L, less manual entry.
- **Effort**: S–M · **Touches**: `finance` (expenses), `platform/storage` · **Migration?**: Maybe

---

## 5. Logistics, courier & staff communication

### 5.1 Real courier API integration ⭐
- **What**: Replace the mock courier with Shiprocket/Delhivery/DTDC — real AWB generation, live tracking, auto status sync into the order workflow.
- **Why**: Removes manual status updates and drives accurate customer WhatsApp notifications. All courier is currently mock.
- **Effort**: L–XL · **Touches**: `courier`, `notification`, `order` workflow · **Migration?**: Maybe (provider config)

### 5.2 Pincode serviceability check at order entry 🔥
- **What**: Check if a pincode is COD-serviceable / deliverable before confirming a salesperson order; estimate delivery days.
- **Why**: Prevents taking orders that can't be fulfilled → fewer RTOs and refunds.
- **Effort**: M · **Touches**: `order` entry, `courier`, `geo` · **Migration?**: Maybe (pincode master)

### 5.3 Bulk label print + courier handover manifest ⭐
- **What**: Select many packed orders → one combined label PDF + a manifest to sign at courier handover.
- **Why**: Speeds up the daily dispatch batch; the packer currently prints one at a time.
- **Effort**: S–M · **Touches**: `label`, `packing` · **Migration?**: No

### 5.4 NDR (non-delivery report) management ⭐
- **What**: A workflow for failed-delivery attempts — capture reason, trigger re-attempt, log the customer contact, decide RTO. Ties into the DELIVERY_FAILED / CUSTOMER_REJECTED states already modelled.
- **Why**: Recovers otherwise-lost deliveries.
- **Effort**: M · **Touches**: `order` workflow, `notification`, `crm` · **Migration?**: Maybe

### 5.5 Real WhatsApp Business API (staff/ops-sent) 🔥
- **What**: Wire the existing notification outbox to a live WhatsApp BSP (Gupshup / Meta Cloud API). The per-step customer messages are already built and mock.
- **Why**: Everything is built for it — this just plugs in the real sender. Immediate perceived value. (Messages are triggered by the workflow/ops, not a customer-facing app.)
- **Effort**: M · **Touches**: `notification` (WhatsApp drainer), `platform` · **Migration?**: No

### 5.6 Ops WhatsApp template broadcasts to a segment ⭐
- **What**: Staff send an approved-template WhatsApp broadcast to a customer segment (reorder push, festival offer) from the dashboard.
- **Why**: Turns the customer list into a reactivation tool. Depends on 5.5 + segments (1.4). Still fully staff-initiated.
- **Effort**: M–L · **Touches**: `notification`, `crm`, new campaign module · **Migration?**: Yes (campaigns, sends)

---

## 6. Analytics, insights & reporting (staff dashboards)

### 6.1 Sales targets & incentive tracking 🔥
- **What**: Set monthly targets per salesperson; dashboard shows attainment %, a leaderboard, and computed incentive.
- **Why**: Directly motivates the sales team; orders-by-salesperson data already exists.
- **Effort**: M · **Touches**: `reporting`, `dashboard`, new targets · **Migration?**: Yes (`sales_targets`)

### 6.2 Scheduled / exportable reports (PDF + Excel) ⭐
- **What**: Any report → download as Excel/PDF, and optionally email on a schedule (the daily report already emails).
- **Why**: The accountant and owner want offline copies; extends the existing DailyReport pattern.
- **Effort**: M · **Touches**: `reporting`, `mail/report` · **Migration?**: No

### 6.3 Cohort / retention analysis 💡
- **What**: Repeat-purchase rate, months-to-reorder, cohort retention curves — all internal analytics.
- **Why**: Understand the recurring nature of the herbal customer base.
- **Effort**: M · **Migration?**: No

### 6.4 Configurable dashboard widgets 💡
- **What**: Let each role rearrange / pick their dashboard KPI tiles.
- **Why**: Different roles care about different metrics.
- **Effort**: M · **Migration?**: Yes (user prefs)

### 6.5 Demand & cash forecasting 🧪
- **What**: Simple time-series forecast of per-product demand and expected COD collections.
- **Why**: Better purchasing + cash planning. Extends the insights engine.
- **Effort**: L · **Migration?**: Maybe

---

## 7. Platform, security & operations

### 7.1 Two-factor authentication (2FA) ⭐
- **What**: OTP/authenticator 2FA for ADMIN (and optionally ACCOUNTANT) logins.
- **Why**: The dashboard holds financial data + customer PII; a basic security expectation.
- **Effort**: M · **Touches**: `auth` · **Migration?**: Yes (2FA secrets)

### 7.2 Full audit log viewer + export ⭐
- **What**: A rich, filterable UI over the existing `audit_events` (by actor, entity, action, date) with export.
- **Why**: Data is already captured (V17); this surfaces it for accountability and dispute resolution.
- **Effort**: S–M · **Touches**: `audit`, frontend audit page · **Migration?**: No

### 7.3 Scheduled DB backups + restore/download UI ⭐
- **What**: Scheduled backups (a `storage/backups` folder already exists) with retention and a one-click restore/download.
- **Why**: Data safety on the free-tier host; peace of mind for the owner.
- **Effort**: M · **Touches**: `platform`, `settings` · **Migration?**: No

### 7.4 Granular permissions / custom roles 💡
- **What**: Move from 4 fixed roles to configurable permission sets (e.g. a read-only viewer, a returns-only clerk).
- **Why**: Flexibility as the team grows.
- **Effort**: L · **Touches**: `auth`, `statemachine/TransitionAuthority` · **Migration?**: Yes

### 7.5 Login throttling / brute-force lockout ⭐
- **What**: Lock accounts / throttle after repeated failed logins.
- **Why**: Protects the internet-facing dashboard login. **Worth confirming whether any throttling exists today.**
- **Effort**: S · **Touches**: `auth`, `platform` · **Migration?**: Maybe

### 7.6 Session / login activity management 💡
- **What**: See active sessions, last login per user, and force-logout.
- **Why**: Security hygiene with shared/mobile access.
- **Effort**: S–M · **Migration?**: Maybe

---

## 8. Staff mobile & UX

### 8.1 PWA / installable app + offline draft order ⭐
- **What**: Make the admin app an installable PWA; allow a salesperson to start an order offline and sync on reconnect.
- **Why**: Staff are mobile-first and often on patchy networks; the UI is already mobile-first.
- **Effort**: M–L · **Touches**: frontend build, service worker · **Migration?**: No

### 8.2 Phone-camera barcode/QR scan for packing ⭐
- **What**: Use the phone camera for the packing scan step (labels are already Code128 barcodes).
- **Why**: No dedicated scanner needed — the packer uses their phone.
- **Effort**: M · **Touches**: `packing` frontend · **Migration?**: No

### 8.3 Web push notifications to staff 💡
- **What**: Browser push for new orders to approve, follow-ups due, low stock.
- **Why**: Faster reaction than checking the in-app bell.
- **Effort**: M · **Touches**: `notification`, frontend · **Migration?**: Yes (push subscriptions)

### 8.4 In-app staff announcement banner 💡
- **What**: Admin posts a notice all staff see on login (policy change, target push).
- **Why**: Internal comms without WhatsApp groups.
- **Effort**: S · **Touches**: `adminnotification` · **Migration?**: Maybe

---

## 9. Shopify boundary & integration (important for this pivot)

Because the storefront is on Shopify, the biggest integration questions are about keeping the OMS
and Shopify consistent. These are worth deciding on:

### 9.1 Shopify order sync into the OMS 🔥
- **What**: Pull Shopify orders into the OMS automatically (webhook/API) so ALL orders — storefront + salesperson — flow through one fulfilment pipeline.
- **Why**: Otherwise Shopify orders never reach packing/courier/reconciliation. This is likely the single most important integration to confirm.
- **Effort**: L · **Touches**: `order`, new `platform`/integration module, webhook endpoint · **Migration?**: Yes (external order id/source)

### 9.2 Inventory sync with Shopify ⭐
- **What**: Keep stock levels consistent between OMS and Shopify (two-way or OMS-as-source-of-truth).
- **Why**: Prevents overselling if both channels decrement the same SKUs. Directly affects bundles (2.4) and batch tracking (3.1).
- **Effort**: L · **Touches**: `inventory`, integration module · **Migration?**: Maybe

> **Open question:** Is Shopify integration expected at all, or is the OMS strictly for salesperson-captured
> orders while Shopify is fulfilled separately? This decision gates §9 and affects §2.4 and §3.

---

## 10. Domain-specific priorities (herbal / D2C India)

The items with the strongest strategic fit for this business:

| Feature | Why it matters here | Effort |
|---|---|---|
| **Batch + expiry tracking (3.1)** | Consumable health products; legal labelling | L |
| **GST/GSTR exports (4.1)** | Statutory India requirement | M |
| **Payment links for manual orders (4.4)** | Kills COD/RTO loss on phone orders | L |
| **Customer RTO risk score (1.2)** | Protects COD margin at entry time | M |
| **Reorder reminder tasks (2.3)** | Repeat-consumption revenue engine | M |
| **Pincode serviceability (5.2)** | Fewer failed deliveries | M |
| **Real WhatsApp BSP (5.5)** | Infra already built for it | M |
| **Shopify order sync (9.1)** | One fulfilment pipeline for all channels | L |

---

## Suggested first wave (recommendation)

Best value-to-effort, reusing existing infrastructure, all strictly admin-dashboard:

1. **Real WhatsApp Business API (5.5)** — infra done, just wire the sender. 🔥 M
2. **GST/GSTR exports (4.1)** — data already captured, statutory value. 🔥 M
3. **Quick reorder from past order (2.1)** — tiny effort, daily time-saver. 🔥 S
4. **Accounts receivable ageing (4.2)** — cash-flow visibility, no migration. 🔥 M
5. **Full audit log viewer (7.2)** — data exists, quick UI win. ⭐ S–M

Second wave (higher effort, high fit): **Shopify order sync**, **Batch/expiry tracking**,
**Payment links for manual orders**, **Reorder reminder tasks**, **Customer RTO risk score**.

---

## Open questions before building

- **Is Shopify order/inventory sync (§9) expected?** This is the biggest scope question and gates several items.
- Is a real **WhatsApp BSP** account (Gupshup / Meta Cloud) provisioned or budgeted?
- Is a **payment gateway** (Razorpay/PhonePe) account available for payment links on manual orders?
- Which **courier(s)** does Shifa actually use? (Determines the 5.1 target.)
- Does the accountant need a specific **GST filing format** (their CA's template)?
- Do products have a defined **shelf life / reorder cycle** we can capture for 3.1 / 2.3?
- Is there any **login throttling** today (7.5)?

> Keep this file updated as items move from idea → spec → shipped. When something ships, move it to
> "What we already have" and update `project-memory.md` + `README.md` in the same change.
