# Requirements Document

Product Catalog, Price Bands, Per-Product GST & Order Discounts

## Introduction

Shifa OMS currently models each product with a single `salePrice`, an `mrp`, one `gstRate`, and an
`hsnCode`. The business needs a richer catalog and pricing model driven by the official Shifa Herbal
Remedies price list (30 products, see Appendix A):

- Each product carries **three price points** — a **Minimum Rate** (floor), an **Auto-Fetch Rate**
  (the default selling price), and an **MRP** (ceiling / maximum) — all of which are shown to staff.
- Each product carries its **own GST rate** (0%, 5%, or 18%) and its **HSN code**.
- Each product records a **weight/volume** descriptor (`Wt/ml`, e.g. "100ML", "60 TB PP", "Combo").
- When placing an order, a salesperson (or any order-entry role) picks a **selling price per line**
  that defaults to the Auto-Fetch Rate and must stay within `[Minimum Rate, MRP]`.
- An order-entry user may apply a **discount** to the order — either a **flat amount** or a
  **percentage**.
- GST is computed **per line** using that line's product GST rate, and the order shows an **aggregate
  GST** figure across all lines.
- The catalog is replaced so that **only the 30 listed products** are orderable going forward, while
  **historical orders retain their originally captured prices and tax** (financial/tax integrity).

This document defines the requirements for that capability. It is built on the current `Oracle_Deployment`
(V48) codebase, which does not contain the reverted "product price band catalog" work.

### Assumptions & Default Decisions (open for confirmation)

These are the working answers to the open questions raised during scoping. They are marked so you can
confirm or change them; the acceptance criteria below encode them.

- **[D1] GST is price-inclusive.** The Minimum/Auto-Fetch/MRP values in the price list are treated as
  **GST-inclusive** rupee amounts (consistent with the existing system, which rounds to whole rupees and
  treats prices as GST-inclusive). Per-line GST is therefore computed as the tax component *extracted*
  from the (discounted) line total, not added on top.
- **[D2] Historical orders are immutable.** Existing orders keep the exact prices, discounts, GST, and
  line items captured at order time. The catalog change does **not** re-point or re-price any existing
  order line.
- **[D3] Old products are deactivated, not deleted.** Products not in the new list are set to a
  non-orderable / hidden state (retained so historical order line items and reports still resolve). No
  product row referenced by history is hard-deleted.
- **[D4] Discount is order-level.** A single discount (flat OR percentage) applies to the whole order,
  entered by the order-entry user. It is applied to the sum of line totals.
- **[D5] Selling price band is enforced.** The per-line selling price must be within
  `[Minimum Rate, MRP]`; values outside are rejected.

## Glossary

- **Minimum Rate**: The lowest price at which a product may be sold on a line (per-line floor).
- **Auto-Fetch Rate**: The default selling price pre-filled on a new order line for a product.
- **MRP (Maximum Retail Price)**: The highest price at which a product may be sold (per-line ceiling).
- **Price band**: The inclusive range `[Minimum Rate, MRP]` for a product's selling price.
- **Selling price**: The per-line price actually charged, chosen by the order-entry user within the band.
- **Line total**: `selling price × quantity` for an order line (GST-inclusive per [D1]).
- **Order-entry role**: Any role permitted to create orders (currently ADMIN, SALESPERSON, TEAM_LEAD).
- **Order discount**: A flat rupee amount or a percentage applied to an order's line-total sum.
- **Per-line GST**: The GST amount attributable to a single line, using that product's GST rate.
- **Aggregate order GST**: The sum of per-line GST amounts for the order.
- **HSN code**: The Harmonised System of Nomenclature tax classification code for a product.
- **Wt/ml**: A human-readable pack size / weight / volume descriptor for a product.
- **Active/orderable product**: A product in the new catalog, visible for selection during order entry.

## Requirements

### Requirement 1: Extended product pricing & tax model

**User Story:** As an admin, I want each product to store a minimum price, an auto-fetch (default) price,
an MRP, its own GST rate, its HSN code, and a weight/volume descriptor, so that pricing and tax reflect
the official price list.

#### Acceptance Criteria

1. THE system SHALL persist, for each product, a Minimum Rate, an Auto-Fetch Rate, an MRP, a GST rate, an
   HSN code, and a Wt/ml descriptor.
2. WHERE a product is created or updated, IF the Minimum Rate is greater than the Auto-Fetch Rate, OR the
   Auto-Fetch Rate is greater than the MRP, THEN THE system SHALL reject the change with a validation error.
3. THE system SHALL accept a per-product GST rate restricted to the set {0, 5, 18} (percent).
4. THE system SHALL store the HSN code as free text of up to 8 digits and the Wt/ml descriptor as free
   text of up to 32 characters.
5. WHERE existing products already have `mrp` and `salePrice` values, THE system SHALL migrate `salePrice`
   into the Auto-Fetch Rate and `mrp` into the MRP, and SHALL default the Minimum Rate to the Auto-Fetch
   Rate when no explicit minimum is provided.
6. THE system SHALL retain backward compatibility for existing reads of `salePrice`/`mrp` (e.g. reports,
   order history) by continuing to expose those values or their equivalents.

### Requirement 2: Load the official product catalog

**User Story:** As an admin, I want the 30 products from the official price list loaded into the system
with correct prices, GST rates, HSN codes, and pack sizes, so that staff order from the approved catalog.

#### Acceptance Criteria

1. THE system SHALL create/seed the 30 products listed in Appendix A with their exact Name, HSN code, GST
   rate, Minimum Rate, Auto-Fetch Rate, MRP, and Wt/ml values.
2. WHERE a product from Appendix A already exists (matched by a stable key such as name or SKU), THE
   system SHALL update it in place rather than creating a duplicate.
3. THE system SHALL mark all 30 catalog products as active/orderable and visible for order entry.
4. WHERE a price-list row has no Wt/ml value (e.g. "Shifa honey Small/Large"), THE system SHALL store an
   empty/blank Wt/ml without error.
5. THE catalog load SHALL be idempotent — running it more than once SHALL NOT create duplicates or alter
   already-correct values.

### Requirement 3: Restrict the orderable catalog to the new list

**User Story:** As an admin, I want only the approved products to be orderable, so that staff cannot place
orders against obsolete items.

#### Acceptance Criteria

1. WHEN the new catalog is loaded, THE system SHALL set every product not in Appendix A to a
   non-orderable / hidden state (per [D3], without deleting it).
2. WHILE a product is in the non-orderable state, THE system SHALL exclude it from the order-entry product
   picker and from active-catalog listings.
3. THE system SHALL NOT hard-delete any product that is referenced by an existing order line, so that
   historical orders and reports continue to resolve product names and details.
4. WHERE a deactivated product is referenced by history, THE system SHALL still return its details in
   read contexts (order detail, reports) while keeping it out of order-entry selection.

### Requirement 4: Preserve historical order integrity

**User Story:** As an accountant, I want past orders to keep their original prices, discounts, and taxes,
so that financial and tax records remain accurate after the catalog change.

#### Acceptance Criteria

1. THE system SHALL NOT recompute or re-point prices, GST, or line items of any order created before the
   catalog change.
2. WHERE a historical order references a product whose price or GST rate later changes, THE system SHALL
   continue to display the order using the values captured at order-creation time.
3. THE system SHALL capture, on each new order line at creation time, the selling price and the GST rate
   used, so that later catalog edits do not alter that order.

### Requirement 5: Per-line selling price within the price band

**User Story:** As a salesperson, I want the order line to default to the product's auto-fetch price and
let me adjust it within the allowed range, so that I can price correctly without exceeding limits.

#### Acceptance Criteria

1. WHEN a product is added to an order line, THE system SHALL pre-fill the line's selling price with that
   product's Auto-Fetch Rate.
2. WHERE an order-entry user edits a line's selling price, IF the value is below the product's Minimum
   Rate OR above its MRP, THEN THE system SHALL reject the line with a validation error identifying the
   allowed range.
3. THE system SHALL display, for each order line during entry, the product's Minimum Rate, Auto-Fetch
   Rate, MRP, and Wt/ml.
4. THE system SHALL compute each line total as `selling price × quantity`.

### Requirement 6: Order-level discount (flat or percentage)

**User Story:** As an order-entry user, I want to apply a flat or percentage discount to an order, so that
I can offer approved reductions to customers.

#### Acceptance Criteria

1. THE system SHALL allow an order-entry user to specify at most one discount per order, of type either
   FLAT (rupee amount) or PERCENT.
2. WHERE the discount type is PERCENT, IF the value is not within 0–100, THEN THE system SHALL reject it.
3. WHERE the discount type is FLAT, IF the amount is negative OR exceeds the order's line-total sum, THEN
   THE system SHALL reject it.
4. WHEN a discount is applied, THE system SHALL reduce the order total by the discount (percentage applied
   to the line-total sum, flat applied directly) and SHALL record the discount type and value on the order.
5. WHERE no discount is entered, THE system SHALL treat the order discount as zero.
6. THE system SHALL make the discount option available to every order-entry role (currently ADMIN,
   SALESPERSON, TEAM_LEAD).

### Requirement 7: Per-line GST calculation

**User Story:** As an accountant, I want each order line's GST computed from that product's own GST rate,
so that mixed-rate orders are taxed correctly.

#### Acceptance Criteria

1. THE system SHALL compute each line's GST using that line's product GST rate.
2. WHERE prices are GST-inclusive per [D1], THE system SHALL extract the GST component from the
   (post-discount) line amount rather than adding tax on top.
3. WHERE an order-level discount exists, THE system SHALL apportion the discount across lines before
   extracting per-line GST, so that the taxable base reflects the discounted amounts.
4. THE system SHALL round monetary results consistently with the existing rounding policy (whole-rupee
   order total; documented rounding for tax components).

### Requirement 8: Aggregate order GST and totals

**User Story:** As a staff member, I want the order to show the overall GST and a clear price breakdown,
so that the customer and records reflect the correct totals.

#### Acceptance Criteria

1. THE system SHALL compute the aggregate order GST as the sum of the per-line GST amounts.
2. THE system SHALL present, for each order, the line-total sum (subtotal), the discount, the aggregate
   GST, and the final payable total.
3. WHERE lines have differing GST rates, THE system SHALL still produce a single correct aggregate GST and
   MAY present a per-rate breakdown.
4. THE final payable total SHALL equal the discounted line-total sum (GST-inclusive per [D1]) after
   whole-rupee rounding.

### Requirement 9: Product management UI for the new fields

**User Story:** As an admin, I want to view and edit the minimum/auto-fetch/MRP prices, GST rate, HSN
code, and Wt/ml for each product, so that I can maintain the catalog.

#### Acceptance Criteria

1. THE product management screens SHALL display Minimum Rate, Auto-Fetch Rate, MRP, GST rate, HSN code,
   and Wt/ml for each product.
2. WHERE an admin edits a product, THE system SHALL validate the price-band ordering (Req 1.2) and the
   allowed GST set (Req 1.3) before saving.
3. THE system SHALL surface validation errors inline with a clear message.

### Requirement 10: Order-entry UI for pricing, GST, and discount

**User Story:** As an order-entry user, I want the order form to show each product's price band and pack
size, let me set the selling price and a discount, and show me the GST and totals live, so that I can
place correctly-priced orders.

#### Acceptance Criteria

1. THE order-entry form SHALL show, per line, the product's Minimum Rate, Auto-Fetch Rate, MRP, and Wt/ml,
   with the selling price defaulting to Auto-Fetch (Req 5.1).
2. THE order-entry form SHALL provide a discount control allowing selection of FLAT or PERCENT and a value
   (Req 6).
3. WHILE the user edits lines or the discount, THE system SHALL display a live breakdown of subtotal,
   discount, aggregate GST, and final total.
4. WHERE the user enters an out-of-band selling price or invalid discount, THE system SHALL block submit
   and show the reason.

## Appendix A — Official product price list (source of truth)

GST rate is percent; Minimum/Auto-Fetch/MRP are GST-inclusive rupees (per [D1]).

| # | Product Name | HSN | GST% | Minimum | Auto-Fetch | MRP | Wt/ml |
|---|---|---|---|---|---|---|---|
| 1 | Beard wash | 33059040 | 5 | 600 | 1000 | 1200 | 100ML |
| 2 | Blue Cream (tiger) | 30049011 | 5 | 600 | 800 | 1000 | 5gm |
| 3 | Breath Pure (Rogan Hayat) Rollon | 30049011 | 5 | 600 | 800 | 1000 | 10ml |
| 4 | Breath Pure (Rogan Hayat) Droper | 30049011 | 5 | 600 | 800 | 1000 | 10ml |
| 5 | Exotic Khajoor | 8041090 | 5 | 150 | 200 | 250 | 100gm |
| 6 | Eye Drop 10 ML pack | 30049011 | 5 | 600 | 800 | 1000 | 10ml |
| 7 | Face Cream | 3304 | 18 | 800 | 1000 | 1200 | 80gm |
| 8 | Facewash | 3304 | 18 | 600 | 800 | 1000 | 100ml |
| 9 | Farba cream | 3004 | 5 | 800 | 1000 | 1200 | 10gm |
| 10 | Capsules Power Gold | 30049011 | 5 | 140 | 180 | 200 | 1 Pc |
| 11 | Gut Cleanser | 12119060 | 5 | 800 | 1100 | 1500 | 350gm |
| 12 | HAIR OIL | 3305 | 18 | 600 | 800 | 1499 | 100ML |
| 13 | Hair oil & Shampoo pack | 3305 | 18 | 1200 | 1400 | 2600 | Combo |
| 14 | Hair Shampoo | 3305 | 18 | 600 | 800 | 1499 | 100ML |
| 15 | Height Heal | 2106 | 5 | 800 | 1100 | 1500 | 350gm |
| 16 | Immuno booster | 21069099 | 5 | 800 | 1100 | 1500 | 350gm |
| 17 | Joint Heal | 21069099 | 5 | 800 | 1100 | 1500 | 150gm |
| 18 | joint heal oil | 3004 | 5 | 800 | 1100 | 1500 | 100ml |
| 19 | Joint heal plus capsule | 3305 | 5 | 800 | 1100 | 1500 | 30 Pc cpsl |
| 20 | Madhumeh | 3004 | 5 | 1100 | 1450 | 1950 | 60 Pc cpsl |
| 21 | Maqwi Mumsik Majun | 3003 | 5 | 1000 | 1400 | 1600 | 80gm |
| 22 | Marham Shifa | 3004 | 5 | 600 | 800 | 1000 | 40gm |
| 23 | Mind Cure Syrup | 3004 | 5 | 1100 | 1450 | 1950 | 300 ml |
| 24 | Ubtun Powder | 3304 | 18 | 800 | 1000 | 1200 | 150gm |
| 25 | PET KAM | 3004 | 5 | 800 | 1100 | 1500 | 100gm |
| 26 | Power Gold tablets | 30049011 | 5 | 800 | 1100 | 1500 | 60 TB PP |
| 27 | Semen Booster | 21069099 | 5 | 800 | 1100 | 1500 | 150gm |
| 28 | Shifa honey Small | 409 | 0 | 300 | 450 | 600 | |
| 29 | Shifa honey Large | 409 | 0 | 350 | 500 | 699 | |
| 30 | Sujan Syrup | 3004 | 5 | 1100 | 1450 | 1950 | 300 ml |
