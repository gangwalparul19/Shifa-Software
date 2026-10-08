# Shifa OMS — User Manual

**Shifa Herbal Remedies — Order Management System**

This is the complete, end-to-end user manual for everyone who works in Shifa OMS. It explains how to
sign in, find your way around, and use every feature. If you only want the parts for your job, open
your role guide in `docs/role-guides/` — but this manual covers everything.

> **Note on screenshots:** this manual uses described screens rather than live images. The production
> system holds real customer data and should not be used for documentation captures. If you want
> screenshots, take them from a local development instance.

---

## Table of contents

1. [Getting started](#1-getting-started)
2. [Signing in and your account](#2-signing-in-and-your-account)
3. [Finding your way around](#3-finding-your-way-around)
4. [The dashboard](#4-the-dashboard)
5. [Orders](#5-orders)
6. [Taking a new order](#6-taking-a-new-order)
7. [Approving orders](#7-approving-orders)
8. [Payment verification](#8-payment-verification)
9. [Packing and fulfilment](#9-packing-and-fulfilment)
10. [Delivery, courier and tracking](#10-delivery-courier-and-tracking)
11. [Returns, RTO and cancellations](#11-returns-rto-and-cancellations)
12. [Customers (CRM)](#12-customers-crm)
13. [Leads](#13-leads)
14. [Products and inventory](#14-products-and-inventory)
15. [Procurement](#15-procurement)
16. [Reconciliation and COD](#16-reconciliation-and-cod)
17. [Reports](#17-reports)
18. [Finance, GST and accounting](#18-finance-gst-and-accounting)
19. [Analytics, insights and teams](#19-analytics-insights-and-teams)
20. [Notifications and announcements](#20-notifications-and-announcements)
21. [Settings and administration](#21-settings-and-administration)
22. [WhatsApp and customer messaging](#22-whatsapp-and-customer-messaging)
23. [Mobile app (PWA) and offline use](#23-mobile-app-pwa-and-offline-use)
24. [Glossary](#24-glossary)
25. [Troubleshooting](#25-troubleshooting)

---

## 1. Getting started

Shifa OMS is a web application you open in a browser. It is **mobile-first**: it works well on a phone
and on a laptop/desktop.

- **On a phone/tablet**: you get a top bar with a menu button (☰), and a bar of four main tabs along
  the bottom for your most-used tasks.
- **On a laptop/desktop** (screen ≥ ~992px wide): you get a fixed menu down the left side instead of
  the bottom bar.

What you can see and do depends on your **role**. You are given one role when your account is created.
See [section 2](#2-signing-in-and-your-account).

---

## 2. Signing in and your account

### Signing in
1. Open the application URL in your browser.
2. Enter your **username** and **password**, then select **Sign in**.
   - Salespeople usually sign in with their **mobile number** as the username.
3. You land on your role's home screen:
   - Admin, Salesperson, Team Lead, Accountant → **Dashboard**
   - Packing user → **Packing**
   - Payment verifier → **Payments**
   - CA → **GST & Accounting**

### First login / password reset
- If an admin reset your password, you'll be sent to a **Change password** screen and must set a new
  password before you can continue.
- You can change your own password anytime from the account menu.

### Your session
- You stay signed in while you work. If your session quietly expires mid-task, the app refreshes it in
  the background so you usually won't notice. If it can't, you'll be returned to the sign-in screen.
- All dates and times in the app are shown in **India Standard Time (IST)**.

### Account menu
Open the user chip (top-right on desktop, or in the drawer footer on mobile) to:
- See your name and role.
- Go to **My Profile**.
- Replay the guided tour.
- **Sign out**.

### My Profile (every staff member)
- View your profile details.
- Submit changes to your details (name, email, mobile, address, date of birth, ID details).
  **Your changes are not applied immediately** — they go to an admin as a **change request** and take
  effect only once approved. Photo and government-ID changes are handled by an admin.

---

## 3. Finding your way around

### The menu
- **Mobile:** tap ☰ (top-left) to open the drawer. Items are grouped (Orders, CRM, Catalog, etc.) and
  groups expand/collapse. Tap your destination.
- **Desktop:** the same groups appear as a permanent left sidebar. The group containing your current
  page opens automatically.

You only see menu items your role is allowed to use.

### The bottom tabs (mobile)
Four tabs for your role's most common tasks — see your role guide for the exact four.

### Global search / command palette
Press **Ctrl+K** (or **Cmd+K**) anywhere to open search. Type to find orders, customers, products, and
jump straight to quick actions (New order, Approval queue, Payments, etc.) relevant to your role.

### Notifications bell
The bell (top bar) shows unread notifications addressed to you or your role. New ones arrive live.

### Page layout conventions
- **Lists** show as cards on a phone and as tables on a desktop. Every list is **paginated** — use the
  pager at the bottom and the rows-per-page selector.
- **Filters** sit at the top of list pages (status tabs, search box, advanced filters).
- **Detail drawers** slide in from the right when you tap/click a row; they use tabs to avoid long
  scrolling.
- **Status pills** are colour-coded (green = good/done, amber = waiting, red = problem, blue = in
  progress).

---

## 4. The dashboard

Your dashboard is shaped by your role.

- **Admin:** business KPIs, a "needs attention" strip (approvals waiting, exceptions, awaiting
  handover — all clickable), a channel overview (Portal vs Shopify vs All) with a period filter, a
  team-wise sales widget, and live activity/notifications.
- **Salesperson:** "My day" (today's orders/revenue, monthly target progress, amount to collect), a
  task inbox (reorder-due + win-back customers), win-back and reorder lists, and your lead pipeline.
- **Team Lead:** team-scoped order status, team highlights (top performer, best lead source, delivery
  success), and a link to full Team Performance.
- **Accountant:** money/receivables KPIs with clickable tiles into Reconciliation.
- **Payment verifier / CA / Packing:** you are taken straight to your work screen (Payments / GST /
  Packing) rather than a generic dashboard.

Tiles are clickable and deep-link into the relevant page.

---

## 5. Orders

**Menu: Orders → Orders.** Every staff role can open this; a salesperson sees only their own orders, a
team lead sees their team's, and admin/accountant/CA see all.

### Finding orders
- **Status tabs** group orders into business stages: Pending Approval, Processing, Shipped, Delivered,
  Returned/Failed, Cancelled (and Rejected). Salespeople see friendly stage labels.
- **Search** by order code, customer name, or mobile. Typing a search jumps the tab back to "All" so
  you find the order wherever it is.
- **Advanced filters:** status group, payment status, source (Portal/Shopify), date, and (admin) a
  specific salesperson. Save a filter combination as a **saved view**.
- **Showing orders by `<name>`** chip appears when you drilled in from a leaderboard/salesperson.

### Reading an order
Click a row to open the detail drawer, which has tabs:
- **Details:** order date, customer, address (with country for international orders), WhatsApp/call
  buttons, note, fulfilment info (handed-to, package count), the salesperson, and shipment/QuikShipX
  status + AWB + a **Track shipment** link. Download the **invoice** here.
- **Items:** line items (with product thumbnails), subtotal, discount, GST (inclusive), total.
- **Payment:** payment status, amount received, verification status, and payment screenshots shown as
  tabbed "snips".

The drawer also shows the status timeline, and admins can expand the raw status history (every
from→to, who, and when).

### Actions on an order (role-dependent)
- **Download invoice** (all who can view).
- **Print label** (admin / packing).
- **Edit:** an admin can edit a PENDING or APPROVED order; the creating salesperson/team lead can edit
  their own order while it is still PENDING. Shopify orders are not editable.
- **Approve / Reject** (admin / accountant) from the drawer or the Approval Queue.
- **Fix & resubmit** on a rejected/payment-rejected order (creator or admin).
- **Reorder:** clone the order's customer + items into a new order.
- **Retry delivery / Reorder** actions appear on RTO/REDISPATCH orders.
- **Export:** export the current filtered list to Excel or CSV.

---

## 6. Taking a new order

**Menu: Orders → New Order.** Available to Salesperson, Admin, and Team Lead.

The form is a short, guided flow (or a single-screen **Quick mode** you can toggle and the app
remembers). Steps:

### Step 1 — Customer
- Enter the **mobile number**. If the customer has ordered before, the app offers to **pre-fill** their
  details from their last order (you can edit anything after).
- Choose a **destination**: **India** (city/state/6-digit pincode — pincode auto-fills city/state) or
  **Outside India** (country + free-text address).
- Choose the **lead source** (WhatsApp, Instagram, Facebook, Google, Offline, Counter Sale, Other…).
  **Counter Sale** means in-house delivery (no courier). The app remembers your last-used source.
- Optional extra details are hidden behind "Add more details" (alternate number, email, buyer GSTIN).
- **Admin only — "Placing this order for":** choose Myself or on behalf of a specific salesperson/team
  lead (the order is then attributed to that person).

### Step 2 — Items
- Add products. Use **Quick add** chips (your best-sellers) and **Frequently bought together**
  suggestions. On a scanner device you can add by **SKU/barcode**.
- Each line shows the allowed price band (minimum–MRP) and the weight. The rate defaults to the sale
  price; you can adjust within the band.

### Step 3 — Payment
- Enter the **amount received**. A **minimum of ₹100 must be collected upfront** (or the full amount
  for small orders). You can take full payment or a partial amount — the balance is **pay on delivery**.
- Upload one or more **payment screenshots** (required when any amount is received). You can attach
  several (e.g. a part payment plus the balance).

### Step 4 — Review
- Check the summary (customer, items, totals, GST, payment) and optionally add an **order note** and a
  **discount** (flat or percent, within limits).
- **Save** the order. It is created as **Pending Admin Approval** (unless auto-approval is enabled and
  the order qualifies).

Helpful behaviors:
- **Duplicate guard:** you can't create a second same-day order for the **same product** for the same
  customer (different items on the same day are fine).
- **Draft recovery:** an unfinished order is saved locally; you'll be offered to resume it next time.
- **Convert from lead / Reorder:** these open New Order pre-filled.

---

## 7. Approving orders

**Menu: Orders → Approval Queue** (Admin). Accountants can also approve from the order drawer.

- The queue lists orders **Pending Admin Approval**, newest first, with a "needs action" count.
- Open an order to review its details and **payment screenshots**.
- **Approve** (sends it into fulfilment: label/AWB is generated and, for courier orders, QuikShipX is
  engaged) or **Reject** with a **reason category** (Rate issue, Address/Pincode issue, Payment issue,
  Other) and a note.
- **Bulk actions:** select multiple orders (including a **Quick select by date** — Today/Yesterday/
  This week/This month) and **Approve** them together. A preview tells you how many will be skipped
  (e.g. ineligible) before you confirm.
- A new order awaiting approval raises a **live toast + chime** for admins.

Rejected and payment-rejected orders can be fixed and resubmitted by the salesperson (same order,
full history kept).

---

## 8. Payment verification

**Menu: Orders → Payments.** For the Payment Verifier (and Admin).

- Prepaid orders arrive in the verification queue, newest first.
- Open an order to see the **payment screenshot(s)** in a tabbed "snip" viewer alongside the expected
  amount and the salesperson.
- **Duplicate proof detection:** a red badge warns if the same screenshot image was used on another
  order.
- **Verify** (payment is genuine) or **Reject** (marks the order **Payment Rejected** with a reason).
  A payment-rejected order is visible to the salesperson, who can fix and resubmit it.

---

## 9. Packing and fulfilment

**Menu: Orders → Packing** (Packing user / Admin). This is the warehouse floor screen.

Every approved order — courier and in-house alike — flows through packing. The page has:

- **KPI tiles:** counts awaiting pack / handover / dispatch.
- **A scan box + camera button:** scan an order's barcode (keyboard scanner or phone camera). The
  barcode encodes the QuikShipX order id when the order is published, else the order code — both resolve.
- **Scan & Move:** scan a parcel to preview the order (customer, status, packaging note) and confirm
  the next step (Pack → Handover → Dispatch).

### Work queues (each paginated, newest first)
1. **Orders to Pack** (Label Generated): print the internal label and/or the QuikShip label, then
   **Mark packed**. Printing the QuikShip label auto-advances the order to Awaiting Handover. Select
   several and **print labels in bulk** (labels print 4-up on an A4 sheet).
2. **Awaiting Handover** (Packed): capture **who took the parcel** (handover name) — single or bulk.
   For a courier order, handover engages the courier (Ready for Pickup).
3. **QuickShip Status** (read-only): AWB + courier status, updated automatically by tracking.
4. **In-House Deliveries:** multi-select orders and **update their status** manually (Out for Delivery,
   Delivered, etc.) — in-house orders have no courier to report progress.

Each queue row shows the order, customer, price, date, salesperson, and the **packaging note** (amber
flag) so special instructions are obvious.

### Other packing tools
- **Pick-list** (Orders → Pick-list): every product needed across all orders awaiting packing,
  aggregated onto one sheet.
- **Mark RTO** (Orders → Mark RTO): scan a returned parcel and mark it Returned to Origin with a reason.
- **Package count:** set how many boxes an order ships in (prints that many label copies).

---

## 10. Delivery, courier and tracking

Shifa delivers two ways:

- **QuikShipX (courier):** the order is created with QuikShipX on punch, confirmed and allotted a
  tracking id/AWB on approval, and tracked automatically. The order drawer shows the QuikShip status,
  **AWB**, and a **Track shipment** link to the courier's public tracking page. Admins have a **Retry
  tracking ID** button if an order gets stuck without an AWB.
- **In-house ("Ishika Enterprise"):** your own team delivers. Staff advance the delivery status
  manually from the packing In-House Deliveries section (or the order drawer), and settle COD on
  delivery.

Customers can be sent a **public tracking link** (`/track/<token>`) that needs no login.

**Shopify Sync** (Admin, under Analytics & Reports): see Shopify orders that got stuck and **recover**
them to QuikShipX in one click; also the master **on/off switch** for the Shopify integration.

---

## 11. Returns, RTO and cancellations

- **Returns** (Orders → Returns; Admin/Accountant/CA view): create/approve/reject returns and refunds,
  mark items restocked. A return raises the correct accounting entries.
- **RTO (Returned to Origin):** the GST-correct way to "give up" on a delivery that failed or was
  refused — it reverses the sale with a **credit note** in the current period. Mark it from the packing
  RTO page.
- **Retry delivery:** a failed/refused delivery can be re-attempted (back to Out for Delivery) instead
  of giving up.
- **Cancel Order** (Orders → Cancel Order; Admin): cancel an order with a note, even after an AWB —
  this also tells the courier to abort the pickup. **Do not cancel a post-invoice order** (it would
  drop an already-filed invoice from GST); use **RTO** instead.
- **Deleted orders** (Admin): review orders that were removed.

---

## 12. Customers (CRM)

**Menu: CRM → Customers.** Admin/Accountant see all; a salesperson sees only customers from their own
orders.

Customers are derived from orders (keyed by mobile number). Open a customer for the **Customer 360**
drawer, with tabs:
- **Overview:** risk level (based on failed vs delivered history), delivery metrics, success rate,
  outstanding amount, first/last order.
- **CRM:** add/remove **tags** and **notes** (a timeline).
- **Orders:** products bought, full order history, and a one-tap **Reorder last order**.

Each customer row has **Call** and **WhatsApp** buttons. The New Order form shows a **prepaid nudge**
for medium/high-risk customers.

---

## 13. Leads

**Menu: CRM → Leads** and **Due follow-ups** (Salesperson / Admin). A salesperson sees only their own
leads.

- **Capture** a lead (name, mobile, source).
- Move it through the pipeline: **New → Contacted → Quoted → Won / Lost** (with a lost reason).
- Set a **follow-up date**; overdue follow-ups are flagged, and the system reminds the owner.
- **Convert** a won lead to an order (opens New Order pre-filled; converting marks the lead Won).
- Lead reports: by source, conversion, pipeline, lost reasons.

---

## 14. Products and inventory

### Products (Catalog → Products)
- Admin manages the catalog; a salesperson has **read-only** access.
- A product has: name, SKU, description, images, a **price band** (minimum / sale / MRP), **HSN code**,
  **GST rate**, weight, visibility, category, and stock settings.
- Product detail shows **sales stats** (this month's revenue + order count).
- Admin can **import products via CSV** (including minimum rate and weight columns).

### Inventory (Catalog → Inventory; Admin)
- See stock levels and movements.
- **Restock** or **adjust** stock, with a reason. Each change is a stock movement with history.

---

## 15. Procurement

**Menu: Procurement** (Admin).
- **Suppliers:** maintain the supplier directory.
- **Purchase Orders:** raise POs to suppliers, track status (ordered/received), and totals. Receiving a
  PO can feed stock.

---

## 16. Reconciliation and COD

**Menu: Orders → Reconciliation** (Admin / Accountant / CA).

- **Receivables:** customer dues and uncollected COD, oldest first.
- **Unsettled COD:** COD still to be collected/remitted.
- **Pending claims:** claim receivables (e.g. from a REDISPATCH/lost parcel).
- **COD aging / SLA:** buckets COD by age (0–7, 8–15, 16–30, 30+ days) and flags anything over the SLA.
- **Courier remittance import:** upload the QuikShipX COD remittance sheet (**CSV or Excel**). The
  system matches each row to an order (by AWB, order code, or QuikShip order id), settles it, and —
  for an order whose delivery webhook was missed — marks it Delivered + COD Collected from the
  remittance row itself. Preview (dry run) first, then confirm.

---

## 17. Reports

**Menu: Analytics & Reports → Reports** (Admin / Accountant / CA; Salesperson sees a scoped subset).

There are **17 report types** across four groups, each exportable to **Excel/PDF**:
- **Sales:** by salesperson, product-wise, customer-wise.
- **Orders:** by status, by lead source, delivery outcome.
- **Money & Receivables:** payments (daily money), outstanding (chase list), COD remittance (pending
  from courier). *(Admin/Accountant/CA only.)*
- **Operations:** expenses, purchase orders, returns, stock. *(Admin/Accountant/CA only.)*

Use the date presets, the summary tiles (and money tiles on finance views), and deep links (e.g. a
product's "View Sales Report"). A **salesperson** sees only their own sales/product/customer reports.

---

## 18. Finance, GST and accounting

### Expenses & P&L (Finance; Admin/Accountant/CA)
- **Expenses:** record business expenses by category.
- **Profit & Loss:** revenue vs expenses for a period.

### GST & Accounting (CA / Admin)
- **GST & Accounting dashboard** (`/ca/gst`): money in/out, a GSTR-3B-style summary with manual ITC and
  net payable, rate-wise / HSN-wise / state-wise taxable breakup, an **Export** segment (outside-India
  orders taxed at 18% IGST), and CSV/PDF export. Period presets include This month, This/Last FY, and
  Indian FY quarters. Click any row to drill into the contributing orders (customer due vs COD pending).
- **GST Filing:** a returns-filing workspace — a calendar of periods, prepare/file/reopen, snapshots,
  and filing-aware export.
- **GST Reconciliation:** five compared figures with drill-down to catch mismatches before filing.

### General Ledger (Accounting; Admin/Accountant post, CA read-only)
A full double-entry accounting module:
- **Chart of Accounts:** the account tree (groups and ledgers).
- **Voucher Entry:** manual double-entry vouchers (and reversal). The system also auto-posts vouchers
  for sales (on approval), purchases, expenses, payment verification, and **COD collection on delivery**.
- **Day Book:** chronological voucher listing.
- **Trial Balance**, **Ledger Statement** (per-account running balance), **Balance Sheet**, **Profit &
  Loss**, and **Cash Flow** reports.

---

## 19. Analytics, insights and teams

- **Analytics** (Admin): sales **targets & incentives**, customer **retention** cohorts, and revenue/
  demand **forecasting**; configurable dashboard tiles.
- **Insights** (Admin): the Statistical Insights Engine surfaces alerts — sales anomalies, low-stock
  reorder, RTO risk, courier scorecard, return-rate anomalies, COD build-up, lead-source conversion.
  Dismiss an insight or **Recompute** on demand.
- **Leaderboard** (Admin / Salesperson): this-month sales ranking + personal order **streak**.
- **Salespeople** (Admin): the salesperson directory + 360 (profile, ID verification, performance).
- **Teams** (Admin): assign salespeople to a team lead.
- **Team-wise Sales** (Admin): per-team rollups with drill-down into salespeople and the individual 360.
- **Team Performance** (Team Lead / Admin): team KPIs, a salesperson leaderboard, lead-source
  conversion, and (for a team lead) a split of **My orders** vs **Team orders**.
- **Exception Center** (Admin): one screen of everything needing attention (approvals waiting, payment
  issues, failed deliveries, unsettled claims, top insights), each with a direct action and Call/
  WhatsApp where a mobile exists.

---

## 20. Notifications and announcements

- **Notifications bell:** live, per-user/role notifications. The **Notifications** page (Admin) is the
  full center.
- **Announcements** (Admin): post banner messages that every staff member sees at the top until
  dismissed.
- **Web Push** (optional): opt in from the notification bell to receive browser push notifications.

---

## 21. Settings and administration (Admin)

- **Settings:** company details, **GST configuration** (GSTIN, state, inclusive/exclusive pricing
  mode), company **logo**, **auto-approval** (enable + max amount), the **Shopify** on/off switch, and
  the **delivery-states** master list.
- **Users:** create/edit staff users, assign roles, activate/deactivate, reset passwords, and edit full
  profile details. (Username is immutable; verification + ID docs live on the Salespeople page.)
- **Profile approvals:** review staff self-service profile change requests (approve/reject).
- **Audit Log:** who did what and when, including field-level diffs for order edits.
- **Backups:** run an on-demand database backup and review history.
- **WhatsApp templates** (Admin/Accountant/Team Lead): add and edit the one-tap message templates with
  placeholders and a live preview.

---

## 22. WhatsApp and customer messaging

- Order and customer screens have **Call** and **WhatsApp** buttons.
- WhatsApp uses **click-to-chat** with customizable templates. Placeholders like `{name}`,
  `{orderCode}`, `{total}`, `{remaining}`, and `{orderSummary}` (an itemized block with paid + COD
  balance) are filled in automatically.
- Managers maintain the templates under **WhatsApp templates**. Keep click-to-chat text to simple
  symbols where it must be reliable on WhatsApp Desktop.

---

## 23. Mobile app (PWA) and offline use

- Shifa OMS is a **Progressive Web App**: you can **Install** it to your phone's home screen or your
  desktop from the top bar.
- It is **offline-aware** — an **Offline** chip appears when you lose connection. Note that creating an
  order requires connectivity (you must collect ₹100 and upload a screenshot), so new orders can't be
  queued offline.
- An update banner appears when a new version is available — reload to get it. If a screen looks
  out-of-date, do a hard refresh (Ctrl+Shift+R) to clear the cached app.

---

## 24. Glossary

| Term | Meaning |
|---|---|
| **AWB** | Air Waybill — the courier's tracking number for a parcel |
| **COD** | Cash/pay on delivery (shown as "Pay on Delivery" / "To collect on delivery") |
| **RTO** | Return to Origin — a parcel sent back; reverses the sale with a credit note |
| **REDISPATCH** | Parcel lost/damaged by the courier; raises a claim and clears customer dues |
| **GST** | Goods and Services Tax; prices are GST-inclusive by default |
| **GSTR-1 / GSTR-3B** | GST return formats produced for filing |
| **HSN** | Harmonised System of Nomenclature — the tax code for a product |
| **ITC** | Input Tax Credit |
| **Lead** | A potential customer being followed up before they order |
| **PWA** | Progressive Web App — installable, offline-aware web app |
| **QuikShipX** | The courier integration used for shipped orders |
| **Ishika Enterprise** | The in-house delivery option (no external courier) |
| **Pay on delivery / COD balance** | The amount still to be collected when the parcel is delivered |
| **Pending Admin Approval** | A new order waiting for an admin/accountant to approve it |

---

## 25. Troubleshooting

| Problem | What to do |
|---|---|
| **"Forbidden" / can't open a page** | Your role doesn't have access. Use the menu — it only shows what you're allowed to use. |
| **Returned to the sign-in screen** | Your session expired. Sign in again. |
| **"This record changed elsewhere"** (409) | Someone else updated it. Refresh the page and try again. |
| **New order won't save — "A payment screenshot is required"** | Attach the payment screenshot in the Payment step (required when any amount is received). |
| **New order blocked as a duplicate** | The customer already has a same-day order for that product. Different items on the same day are allowed. |
| **Order stuck without a tracking id** | Admin: open the order and use **Retry tracking ID**, or use **Shopify Sync** for Shopify orders. |
| **WhatsApp emoji show as "□"** | Use simple symbols in templates; some emoji don't survive WhatsApp Desktop's click-to-chat. |
| **Screen looks out of date after an update** | Hard refresh (Ctrl+Shift+R) or clear the site's app cache. |
| **Can't edit a Shopify order** | Shopify orders are managed automatically and are not editable in the OMS. |

---

*For job-specific step-by-step guides, see the role guides in `docs/role-guides/`. For the technical
reference, see `docs/APPLICATION-MEMORY.md`.*
