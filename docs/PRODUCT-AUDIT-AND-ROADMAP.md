# Shifa OMS — Product Audit & Enhancement Roadmap

A reference document to review **existing usability gaps ("loop holes")** and **new features to add**,
before any code changes. Written with the real team in mind: **~25 users, of whom ~20 are salespeople
with limited digital experience.** The guiding principle throughout is **make the salesperson's daily
path dead-simple; keep power features for Admin/Accountant.**

> Status: analysis only — nothing has been changed in the app. Each item below notes rough **effort**,
> **value**, and **risk**, so you can pick what to build. Chosen items can then become individual specs.

---

## 1. Who uses the app (and what that implies)

| Role | Count | What they do | Design implication |
|---|---|---|---|
| **Salesperson** | ~20 | Capture orders, follow leads, check their own orders | Must be **extremely simple** — big buttons, few fields, plain language, minimal choices |
| **Packing** | few | Pack, print labels, handover, dispatch | Fast, tactile, scanner/print-first |
| **Accountant** | 1–2 | COD reconciliation, expenses, P&L, reports | Data-dense is OK |
| **Admin** | 1–2 | Everything: approvals, config, staff, analytics | Full power |

**Takeaway:** 80% of users are the least technical. The app's success hinges on the **salesperson
experience**, not on adding more admin features.

---

## 2. Current functionality (quick inventory)

- **Order lifecycle:** PENDING_ADMIN_APPROVAL → APPROVED → LABEL_GENERATED → PACKED → HANDED_TO_DELIVERY
  → COURIER_ASSIGNED → DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED/COD_COLLECTED/CLOSED, plus
  CUSTOMER_REJECTED, DELIVERY_FAILED, RTO, REDISPATCH, REJECTED, CANCELLED.
- **Salesperson pages:** New Order, Orders (own), Leads (+ due follow-ups), Customers (own), Products (read), My Profile, dashboard widget.
- **Packing page:** work queues (to pack / awaiting handover / awaiting dispatch), barcode scan, per-order label print, handover, dispatch.
- **Accountant pages:** Reconciliation, Reports, Expenses, P&L, Returns, Customers.
- **Admin pages:** everything above + Approval queue, Inventory, Suppliers, Purchase Orders, Notifications, Announcements, Audit, Users, Salespeople, Insights, Analytics, Backups, Settings.
- **Platform:** JWT/RBAC, S3 file storage, nightly DB backup, WhatsApp/courier in MOCK mode, PWA + offline order capture, SSE live dashboard.

---

## 3. Usability "loop holes" (existing pain points)

These are the biggest friction points for a low-literacy salesperson-heavy team.

### 3.1 Too much on screen for salespeople
- Even though nav is role-guarded, the app *feels* large. A salesperson really needs only: **New Order, My Orders, My Leads.** Everything else is noise for them.
- **Fix:** a stripped-down **Salesperson Home** — one big "➕ New Order" button, a simple "My Orders" list (search by phone), and a "My Leads" tile. Hide/゛collapse everything else.

### 3.2 The New Order form is long and technical
- Many fields on one screen; optional vs required isn't obvious; validation messages are developer-style
  (e.g. *"amountReceived must be a DECIMAL(12,2) value"*, *"postalCode must be exactly 6 digits"*).
- **Fix:** a **guided wizard** (Customer → Items → Payment → Review), plain-language errors
  (*"Enter the 6-digit PIN code"*), clearly optional fields greyed/last, and large touch targets.

### 3.3 Language barrier
- UI is English-only. Many salespeople are more comfortable in **Hindi / regional language**.
- **Fix (high impact):** a **bilingual UI** (English + Hindi) toggle, at least for the salesperson
  screens (New Order, My Orders, status labels, buttons, error messages). This single change likely
  reduces mistakes more than any other.

### 3.4 Typing-heavy address entry → data errors
- City/State/PIN typed by hand → typos, wrong states, mismatched PIN/city. The state typeahead helps, but PIN→city/state is still manual.
- **Fix:** **auto-fill City + State from the 6-digit PIN code** (India Post PIN API / bundled dataset). Salesperson types PIN → city/state auto-filled. Big accuracy + speed win.

### 3.5 Too many statuses exposed
- The full lifecycle (14+ statuses) is meaningful to Admin, confusing to a salesperson who just wants "where is my order?".
- **Fix:** a **simplified status tracker** for salespeople — group into plain stages: *Placed → Approved → Packed → Shipped → Delivered* (with a friendly icon), hiding internal transitions.

### 3.6 Error & empty states
- Some errors are technical; some lists lack helpful empty states / next-step guidance.
- **Fix:** plain-language messages, "what to do next" hints, consistent primary-button placement.

### 3.7 Confirmation on important actions
- Ensure irreversible/important actions (cancel, reject, delete) always confirm with a clear plain-language prompt (mostly present; audit for gaps).

---

## 4. Client-requested features (detailed)

### 4.1 Multi-Label Print (Packaging team)
- **Goal:** print labels for **several orders at once** in one PDF (batch printing at the packing table).
- **Current state:** the backend **already supports this** — `POST /api/admin/labels/internal/bulk`
  returns one combined PDF for a list of order IDs. It is simply **not surfaced in the packing UI**.
- **Work:** add multi-select (checkboxes) to the packing "to pack" queue + a **"Print selected labels"** button that calls the existing bulk endpoint.
- **Effort:** Low (UI wire-up). **Value:** High for packers. **Risk:** Low.

### 4.2 Multi-Pack Option (Packaging team)
- **Goal:** one order shipped in **multiple boxes/packages** — capture the number of packages and produce a label per package (e.g. "Box 1 of 3").
- **Current state:** not modelled — one order = one label.
- **Work:** add `packageCount` (and optionally per-package weight/notes) to the order; generate N labels with "Box X of Y"; reflect in handover/dispatch and courier AWB handling.
- **Effort:** Medium-High (data model + label rendering + migration). **Value:** Medium-High. **Risk:** Medium (touches labels + courier).

### 4.3 Handover popup with handover person's name
- **Goal:** when the packer clicks **Handover**, show a popup to enter **who it was handed to** (courier person / agency name, optionally phone).
- **Current state:** `POST /api/packing/{id}/handover` takes **no data** — no name captured.
- **Work:** add `handoverName` (and optional `handoverPhone`) to the handover request; persist on the order / status-history; show it on the order detail and packing history. Frontend: a small popup before confirming handover.
- **Effort:** Low. **Value:** High (accountability/traceability). **Risk:** Low (additive + migration).

### 4.4 New "Payment Verifier" role + Payment dashboard
- **Goal:** a dedicated role that **verifies each payment's authenticity** (screenshot vs. amount) and handles **customer engagement**, with a **separate Payment dashboard**. New salesperson orders should surface to this person before/alongside admin approval.
- **Current state:** payment screenshot is uploaded at order entry; Admin approves orders. There is no separate verification step or role.
- **Work (significant):**
  - New role `PAYMENT_VERIFIER` (Role enum + security + nav).
  - New workflow step: order created → **PAYMENT_VERIFICATION** (view screenshot, confirm amount, mark **Verified**/**Rejected** with a note) → then Admin approval. (Requires a new order status + state-machine transitions + notifications.)
  - New **Payment dashboard**: queue of orders awaiting verification, screenshot viewer, amount check, verify/reject, customer-engagement notes, filters.
  - Admin dashboard: prominent **new-order alert** (SSE already exists) so admin sees incoming salesperson orders live.
- **Effort:** High (new role + new state + dashboard + tests). **Value:** High (fraud control). **Risk:** Medium-High (changes the core workflow and the 490+ test suite).
- **Design note:** decide whether verification is **before** admin approval (blocks approval) or a **parallel** check. Blocking is cleaner for authenticity control.

### 4.5 Alternate contact number on the order page
- **Goal:** capture a **second phone number** for the customer (for failed-delivery contact).
- **Current state:** only `customerMobile` (10 digits). Easy to add like the existing optional `customerEmail`.
- **Work:** add optional `alternateMobile` (10-digit, nullable) to `CreateOrderRequest`, the order entity (new Flyway migration), order detail view, and the New Order form.
- **Effort:** Low. **Value:** Medium (helps delivery success). **Risk:** Low (additive).

### 4.6 Round order price to the nearest rupee
- **Goal:** ₹2679.99 → ₹2680 (round the order total to the nearest whole rupee).
- **Current state:** totals carry paise.
- **Work:** apply **half-up rounding** to the **grand total**, and — importantly for GST correctness — show a **"Round-off" adjustment line** on the invoice (standard Indian invoicing practice) rather than silently altering the taxable value. Apply consistently across order total, invoice PDF, COD amount, and reports.
- **Effort:** Low-Medium. **Value:** Medium (cleaner cash handling / COD). **Risk:** Medium — **must be validated with the accountant** so GST/tax base and invoice totals reconcile. Add a new migration if a stored round-off field is needed.

---

## 5. Additional suggestions (based on the current design)

### 5.1 High-value additions
- **Bilingual (English/Hindi) UI** — see §3.3. Likely the single biggest error-reducer for this team.
- **PIN-code auto-fill of city/state** — see §3.4.
- **Simplified Salesperson Home + status tracker** — see §3.1, §3.5.
- **Guided New Order wizard** — see §3.2.
- **Go live with WhatsApp order confirmations** (currently MOCK) — customers get updates automatically; reduces "where's my order?" calls to salespeople.
- **Duplicate/risky-customer nudge at order entry** — the Customer-360 risk score + prepaid nudge already exist; make the warning more prominent for HIGH-risk mobiles.
- **Draft orders** — let a salesperson save an incomplete order and finish later (helps offline/interrupted entry).
- **Packing slip** printed alongside the label (item checklist for the packer).

### 5.2 Small quality-of-life fixes
- Plain-language validation messages everywhere.
- Bigger tap targets + fewer taps on the salesperson flow.
- "Search order by phone number" front-and-center for salespeople.
- Consistent confirmation dialogs for important actions.

---

## 6. What to simplify or remove

Removing/hiding reduces confusion for the 20 salespeople more than any feature adds.

- **Trim salesperson navigation to the essentials** (New Order, My Orders, My Leads, My Profile). Make sure nothing else is even visible to them.
- **Unused `CUSTOMER` role** — leftover from the removed storefront. Consider removing it (and any dead storefront-era columns) to reduce confusion. *Low priority; do via migration, don't drop columns casually.*
- **Consolidate overlapping concepts for salespeople:** Leads vs Orders vs Customers can blur together. Consider clearer naming/grouping or merging the salesperson view so they aren't unsure where to go.
- **Reduce visible order statuses** for non-admins (see §3.5).
- **Keep advanced modules (Insights, Analytics, Suppliers, POs, Expenses, P&L) strictly admin/accountant** — verify salespeople never land on them.

> ⚠️ **Do not delete database columns/tables casually.** Prefer hiding in the UI; if truly removing, use a new Flyway migration (V39+) and never edit an applied one. Keep the 490+ test suite green.

---

## 7. Prioritized roadmap

| # | Item | Value | Effort | Risk | Suggested priority |
|---|---|---|---|---|---|
| 1 | Handover name popup (§4.3) | High | Low | Low | **Now** |
| 2 | Alternate contact number (§4.5) | Med | Low | Low | **Now** |
| 3 | Multi-label print UI (§4.1) | High | Low | Low | **Now** (endpoint exists) |
| 4 | Plain-language errors + salesperson nav trim (§3.1/3.6) | High | Low | Low | **Now** |
| 5 | Round-off to nearest rupee (§4.6) | Med | Low-Med | Med (GST) | Soon (validate w/ accountant) |
| 6 | PIN-code auto-fill (§3.4) | High | Med | Low | Soon |
| 7 | Simplified Salesperson Home + status tracker (§3.1/3.5) | High | Med | Low | Soon |
| 8 | Bilingual Hindi UI (§3.3) | Very High | Med-High | Low | Plan |
| 9 | Guided New Order wizard (§3.2) | High | Med | Low | Plan |
| 10 | Multi-pack option (§4.2) | Med-High | Med-High | Med | Plan |
| 11 | Payment Verifier role + Payment dashboard (§4.4) | High | High | Med-High | Plan (biggest) |

**Suggested first sprint (quick, high-value, low-risk):** items 1–4 — small additive changes that noticeably improve the packing + sales experience without touching the core workflow or GST.

---

## 8. Risks & things to confirm before building

- **Rounding vs GST:** round the *total* with a visible round-off line; don't distort the taxable base. Confirm with the accountant and check the invoice PDF + Vyapar export still reconcile.
- **Payment-verification workflow** changes the order state machine and notifications — plan the new status and update tests deliberately.
- **Every schema change = a new Flyway migration** (V39, V40, …); never edit an applied one. Start against consistent data.
- **Keep the test suite green** (currently 490+); features that touch the workflow must update/extend tests.
- **Train the team** on any new flow — with 20 low-experience users, a short in-app help or a one-page guide per change matters as much as the code.

---

## 9. Next steps

1. You pick the items to proceed with (the "Now" set is a safe, high-impact start).
2. Each chosen item becomes its own **spec** (requirements → design → tasks) so we implement it cleanly and testably.
3. We batch the small additive ones (handover name, alternate contact, multi-label UI, round-off) into a single deploy.
