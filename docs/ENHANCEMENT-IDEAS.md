# Shifa OMS — Enhancement Ideas (Productivity & UI/UX)

Fresh, high-value ideas to make the app **faster to use and easier to learn**, tuned to the real
context: a **COD-heavy** herbal-remedies OMS, ~**20 of 25 users are salespeople with limited digital
experience**, everything is **mobile-first**, and hosting is **cost-conscious** (free/low-tier).

> This is a suggestion catalogue — nothing here is built yet. It deliberately **excludes** what's
> already done (alternate contact, handover name popup, multi-label print, round-off, multi-pack,
> Payment Verifier + dashboard, Simplified Salesperson Home, New Order wizard) and the two already
> planned (PIN-code auto-fill, Hindi/bilingual UI).

Each item is tagged **Value** (impact), **Effort**, and **Risk**. A prioritized shortlist is at the end.

---

## 1. Order-entry speed & accuracy (biggest lever for 20 salespeople)

### 1.1 Customer auto-fill from phone number
When the salesperson types a known 10-digit mobile, offer to **auto-fill name + last delivery address**
from that customer's most recent order. One tap instead of re-typing everything.
- Builds on the existing Customer-360 data (orders keyed by mobile) and the duplicate-check endpoint.
- **Value: Very High · Effort: Medium · Risk: Low.** Massively cuts typing and address errors.

### 1.2 One-tap reorder / "repeat last order"
From a customer's history (or the orders list), **"Reorder"** pre-loads a new order with the same items
and address — ideal for repeat herbal customers.
- **Value: High · Effort: Medium · Risk: Low.**

### 1.3 Product quick-add by barcode/scan in order entry
Let the salesperson add a product by scanning/typing its SKU (reuse the packing camera scanner) instead
of scrolling a dropdown.
- **Value: Medium · Effort: Medium · Risk: Low.**

### 1.4 Draft / auto-save orders
Persist a half-finished order (locally first, like the offline queue) so an interrupted salesperson can
resume — and never loses a form to an accidental back-press.
- **Value: High · Effort: Medium · Risk: Low.**

### 1.5 Smart quantity & price defaults
Remember the salesperson's most-sold products and surface them first; default quantity to 1; warn on
unusually large quantity or price edits.
- **Value: Medium · Effort: Low–Medium · Risk: Low.**

---

## 2. Communication & customer engagement

### 2.1 Click-to-WhatsApp everywhere (before full API)
Add **wa.me deep links** (pre-filled message) on the order and customer screens so staff can message a
customer in one tap — usable **today** while the WhatsApp API stays mocked.
- **Value: High · Effort: Low · Risk: Low.** Great interim win.

### 2.2 Post-delivery feedback capture
A short, link-based rating (1–5 + comment) sent after delivery; results roll into the Customer-360 risk
and a simple satisfaction KPI.
- **Value: Medium · Effort: Medium · Risk: Low.**

### 2.3 Win-back nudges
Flag customers with no order in N days on a "Win-back" list for salespeople to call — reuses retention
analytics already in the app.
- **Value: Medium · Effort: Medium · Risk: Low.**

---

## 3. Fulfilment & COD operations

### 3.1 Daily pick-list / packing manifest
A single printable sheet of everything to pack today (grouped by product), so the packer picks stock
once instead of per-order.
- **Value: High (for packers) · Effort: Medium · Risk: Low.**

### 3.2 Courier remittance import & auto-match (COD)
Upload the courier's COD remittance CSV → auto-match to orders → one-click settle, flag mismatches.
Removes the biggest manual reconciliation chore.
- **Value: Very High (accountant) · Effort: High · Risk: Medium.**

### 3.3 Order tags (priority / fragile / gift / repeat)
Lightweight, colour-coded tags on orders for quick triage in queues.
- **Value: Medium · Effort: Low · Risk: Low.**

### 3.4 Bulk actions in more places
Bulk approve exists; extend to **bulk print labels, bulk handover, bulk dispatch, bulk mark-packed**
from the queues (with the multi-select already added for labels).
- **Value: Medium–High · Effort: Medium · Risk: Low.**

### 3.5 Auto-draft purchase orders on low stock
When stock crosses the reorder point, pre-draft a PO to the usual supplier for admin approval — builds
on the existing procurement + low-stock insight.
- **Value: Medium · Effort: Medium · Risk: Low.**

---

## 4. Navigation & findability (productivity)

### 4.1 Global quick-search / command palette (Ctrl-K)
One box to jump to an order by code/phone, a customer, or a page. Huge time-saver for admins/accountants.
- **Value: High · Effort: Medium · Risk: Low.**

### 4.2 Saved filters & views
Let users save common order/report filters ("Today's COD", "Awaiting my approval") and pin them.
- **Value: Medium · Effort: Medium · Risk: Low.**

### 4.3 Keyboard shortcuts for power users
Approve/next/search shortcuts on the approval queue and packing screen.
- **Value: Medium · Effort: Low–Medium · Risk: Low.**

---

## 5. UI/UX polish (learnability for low-literacy staff)

### 5.1 Order status timeline (visual stepper)
Replace the raw status list on order detail with a friendly **vertical timeline** (Placed → Approved →
Packed → Shipped → Delivered) with icons and timestamps.
- **Value: High · Effort: Medium · Risk: Low.**

### 5.2 First-run guided tour + contextual help
A dismissible coach-mark tour on each main screen and small "?" tooltips — critical for onboarding
less-experienced salespeople without a manual.
- **Value: High · Effort: Medium · Risk: Low.**

### 5.3 Icons + colour + plain language everywhere
Pair every action/status with an icon and a plain word; keep colour meanings consistent (green=good,
amber=waiting, red=problem). Reduces reliance on reading.
- **Value: High · Effort: Low (incremental) · Risk: Low.**

### 5.4 Skeleton loaders & optimistic feedback
Show skeletons while loading and immediate optimistic UI on actions, with undo where safe.
- **Value: Medium · Effort: Low–Medium · Risk: Low.**

### 5.5 Relative timestamps
"2 hours ago" alongside exact dates — easier to parse at a glance.
- **Value: Low–Medium · Effort: Low · Risk: Low.**

### 5.6 Dark mode
Optional dark theme (Tabler supports it) — comfort for long shifts.
- **Value: Low–Medium · Effort: Medium · Risk: Low.**

### 5.7 Accessibility pass (WCAG AA-oriented)
Focus outlines, ARIA labels, ≥44px targets (mostly done), colour contrast, full keyboard navigation.
- **Value: Medium · Effort: Medium · Risk: Low.**
- Note: full WCAG compliance requires manual testing with assistive tech + expert review; this is an
  improvement pass, not a certification.

---

## 6. Trust, safety & reliability

### 6.1 Notification preferences per user
Let each user choose which in-app/push notifications they receive, reducing noise.
- **Value: Medium · Effort: Medium · Risk: Low.**

### 6.2 Backup restore + health page (admin)
A small admin "System health" view (DB, storage, last backup, disk) and a guided restore flow to
complement the existing backup download.
- **Value: Medium · Effort: Medium · Risk: Medium.**

### 6.3 Session/rate protections & login hardening
Basic login rate-limiting and clearer session-expiry handling (silent refresh + friendly re-login).
- **Value: Medium · Effort: Medium · Risk: Low–Medium.**

### 6.4 Notification ownership fix (known gap)
`POST /api/notifications/{id}/read` doesn't verify the notification belongs to the caller (already
flagged in project memory). Small correctness/security fix.
- **Value: Medium · Effort: Low · Risk: Low.**

---

## 7. Insight & motivation

### 7.1 Salesperson leaderboard & light gamification
A friendly leaderboard (orders, delivery success, target progress) with streaks/badges — motivates the
sales team; builds on the existing Salesperson-360 + targets.
- **Value: Medium–High · Effort: Medium · Risk: Low.**

### 7.2 "My day" summary for salespeople
A one-glance card: orders today, pending follow-ups, targets, and a nudge for the next best action.
- **Value: Medium · Effort: Low–Medium · Risk: Low.**

---

## 8. Prioritized shortlist (recommended order)

Chosen for **maximum productivity/accuracy gain at low risk**, weighted to the salesperson-heavy team:

| # | Enhancement | Value | Effort | Why first |
|---|---|---|---|---|
| 1 | Customer auto-fill from phone (1.1) | Very High | Med | Cuts the most typing/errors on every order |
| 2 | Click-to-WhatsApp deep links (2.1) | High | Low | Immediate engagement win, no API needed |
| 3 | Order status timeline (5.1) | High | Med | Makes "where's my order?" obvious to everyone |
| 4 | First-run guided tour + tooltips (5.2) | High | Med | Onboards low-experience staff without a manual |
| 5 | One-tap reorder (1.2) | High | Med | Speeds repeat herbal orders |
| 6 | Daily pick-list / manifest (3.1) | High | Med | Big packer time-saver |
| 7 | Global quick-search (4.1) | High | Med | Admin/accountant productivity |
| 8 | Courier COD remittance import (3.2) | Very High | High | Removes the heaviest manual reconciliation |

**Quickest wins (do in a batch):** Click-to-WhatsApp (2.1), Order tags (3.3), relative timestamps
(5.5), notification-ownership fix (6.4), plain-language/icon polish (5.3).

---

## 9. How these fit the constraints

- **Low-literacy salespeople:** items 1.1, 1.2, 5.1, 5.2, 5.3, 7.2 directly reduce reading/typing and
  make the happy path obvious.
- **COD-heavy business:** 3.2 (remittance import) and 3.1 (manifest) target the real operational load.
- **Cost-conscious hosting:** everything here is in-app (no new paid infra); WhatsApp deep links avoid
  API costs until you're ready.
- **Mobile-first:** all UI items assume the phone form factor first.

---

## 10. Suggested next step

Pick a batch (the shortlist's top 3–5 are a strong, low-risk start). Each becomes its own spec
(requirements → design → tasks) and ships behind the same verify-and-build discipline used so far, then
deploys in one go with the current pending work.
