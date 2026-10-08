# Design Document

Product Catalog, Price Bands, Per-Product GST & Order Discounts

## Overview

This feature extends the existing (V48) product and order model. Investigation shows the codebase already
has much of the tax plumbing:

- `Product` already has `mrp`, `salePrice`, nullable `gstRate` (V12), and `hsnCode` (V2).
- `OrderLineItem` already snapshots `hsnCode` + `gstRate` + `rate` + `lineTotal` per line at order time.
- `OrderEntity` already has `discountAmount` (DECIMAL, default 0) + `couponCode` + `applyDiscount(...)`.
- `LineItemRequest` already accepts a nullable per-line `rate` override; `OrderService.priceLines`
  defaults it to `product.salePrice`.
- Totals sum line totals then `roundToWholeRupees()`; pricing is GST-inclusive.

So the work is **additive**: add the missing price-band fields + Wt/ml, enforce the band, add a typed
order discount, compute per-line + aggregate GST, load/seed the official catalog and deactivate the rest,
and surface everything in the UIs. Historical orders are untouched (they already carry snapshots).

Mapping of PDF columns → model:
- **Minimum Rate** → new `products.minimum_rate`
- **Auto-Fetch Rate** → existing `products.sale_price` (the default line rate)
- **MRP** → existing `products.mrp` (the ceiling)
- **GST%** → existing `products.gst_rate`
- **HSN** → existing `products.hsn_code`
- **Wt/ml** → new `products.wt_ml`

## Architecture

```
Order entry (Angular new-order) ──► POST /api/orders (CreateOrderRequest + discount + per-line rate)
                                          │
                                    OrderService.createSalespersonOrder
                                          │  priceLines()  ── enforce [minimum_rate, mrp] per line
                                          │  OrderPricing.compute() (NEW pure domain)
                                          │      • subtotal = Σ line totals
                                          │      • discount = FLAT | PERCENT → amount (apportioned to lines)
                                          │      • per-line GST extracted from discounted, GST-inclusive line
                                          │      • aggregate GST = Σ per-line GST; total = subtotal − discount (rounded)
                                          ▼
                                    OrderEntity (+ discountType, discountValue, discountAmount, gst snapshots)
                                          ▼
                                    OrderResponse (subtotal, discount, gstAmount, per-rate breakdown, total)

Catalog: Flyway V49 (schema) + V50 (seed 30 + deactivate others) ── ProductCatalogSeed (idempotent)
Product admin (Angular products) ──► /api/admin/products (ProductRequest + minimumRate, wtMl)
```

## Components and Interfaces

### 1. Data model / migrations

**V49__product_price_band_and_wtml.sql** (additive, nullable → then backfilled):
- `ALTER TABLE products ADD COLUMN minimum_rate DECIMAL(12,2) NULL;`
- `ALTER TABLE products ADD COLUMN wt_ml VARCHAR(32) NULL;`
- Backfill: `UPDATE products SET minimum_rate = sale_price WHERE minimum_rate IS NULL;` (Req 1.5)

**V50__seed_shifa_price_list.sql** (idempotent catalog load, Req 2 & 3):
- For each of the 30 products (Appendix A of requirements): `INSERT ... ON DUPLICATE KEY UPDATE` matched by
  a deterministic `sku` (generated slug, e.g. `SHIFA-BEARD-WASH`), setting name, hsn_code, gst_rate,
  minimum_rate, sale_price (auto-fetch), mrp, wt_ml, visibility='PUBLISHED', track_inventory as-is.
- Deactivate the rest: `UPDATE products SET visibility='HIDDEN' WHERE sku NOT IN (<the 30 skus>);` (Req 3.1).
  No deletes (Req 3.3).

Order-level discount type/value on `orders` (small, additive) — reuse the migration or add to V49:
- `ALTER TABLE orders ADD COLUMN discount_type VARCHAR(10) NULL;` (FLAT | PERCENT)
- `ALTER TABLE orders ADD COLUMN discount_value DECIMAL(12,2) NULL;` (the raw entered value)
- `discount_amount` (existing) stays as the resolved rupee reduction.

**`Product`** entity: add `minimumRate` (BigDecimal) + `wtMl` (String) with getters/setters; keep the
existing constructor, set new fields via setters (as HSN/GST already are).

### 2. Product service & DTOs (Req 1, 9)

- `ProductRequest`: append `minimumRate` (BigDecimal) + `wtMl` (String) — appended last to minimise churn
  at call sites (CSV import passes null).
- `ProductResponse`: append `minimumRate` + `wtMl`.
- `ProductService.create/update`: set the new fields; add validation `minimumRate ≤ salePrice ≤ mrp`
  (Req 1.2) and `gstRate ∈ {0,5,18}` when present (Req 1.3) via a small `validatePriceBand(...)` helper
  throwing `ValidationException`.
- CSV import (`ProductImportService`): optional `minimumRate`,`wtMl` columns (backward compatible).

### 3. Order pricing domain (Req 5, 6, 7, 8)

New pure class **`order/domain/OrderPricing`** (no Spring, fully unit-testable):

```
record DiscountSpec(DiscountType type, BigDecimal value)   // type: NONE|FLAT|PERCENT
record PricedOrder(Money subtotal, Money discount, Money gstTotal, Money total,
                   List<PricedLineResult> lines, Map<BigDecimal,Money> gstByRate)
record PricedLineResult(... Money lineTotal, Money discountShare, Money taxableBase,
                        Money gstAmount, BigDecimal gstRate)

static PricedOrder compute(List<LineInput> lines, DiscountSpec discount)
```

Rules:
- `subtotal = Σ lineTotal` (GST-inclusive).
- Discount amount: PERCENT → `subtotal × value/100`; FLAT → `value`; validated (Req 6.2, 6.3).
- Apportion discount across lines proportionally to lineTotal (largest-remainder so the parts sum exactly
  to the discount) → each line's `discountShare` (Req 7.3).
- Per-line GST-inclusive discounted amount `net = lineTotal − discountShare`; extract GST (Req 7.2, D1):
  `gstAmount = net − net / (1 + rate/100)`, half-up to 2 dp. Lines with null/0 rate → gstAmount 0.
- `gstTotal = Σ gstAmount`; `gstByRate` groups gstAmount by rate (Req 8.3).
- `total = (subtotal − discountAmount)` then `roundToWholeRupees()` (Req 8.4). GST is *within* the total.

`OrderService.createSalespersonOrder`:
- `priceLines` enforces the band: rate defaults to `salePrice`; if `rate < minimum_rate` or `rate > mrp`
  → `ValidationException` naming the allowed range (Req 5.2). (Legacy products with null minimum_rate use
  salePrice as floor per backfill.)
- Build `DiscountSpec` from the request; call `OrderPricing.compute`; persist `discountType`,
  `discountValue`, `discountAmount`, and use `compute().total()` as the order total (replacing the current
  `totalOf(...).roundToWholeRupees()`), keeping the existing payment classification against that total.
- Per-line entity already stores gstRate; also store the computed per-line `gstAmount`? Not required —
  gstAmount is derivable; we expose it on the response computed on read to avoid a new column. (Order
  immutability preserved because rate+gstRate+discount are all snapshotted.)

### 4. Order DTOs (Req 8, 10)

- `CreateOrderRequest`: append `discountType` (String enum) + `discountValue` (BigDecimal), both optional.
- `OrderResponse`: append `subtotalAmount`, `gstAmount`, and `discountType`/`discountValue`
  (keep existing `discountAmount`). `LineItemResponse`: append `gstAmount`. These are computed via
  `OrderPricing.compute` over the persisted lines + stored discount at read time (single source of truth),
  so list/detail/invoice agree.

### 5. Frontend

**Products** (`products/`): model + service + form gain `minimumRate` + `wtMl`; the form's Pricing tab
shows Minimum / Auto-Fetch (sale) / MRP + GST + HSN + Wt/ml with band validation; list shows Wt/ml.

**New Order** (`orders/new-order`): each line shows the product's Min / Auto-Fetch / MRP + Wt/ml; the rate
input defaults to Auto-Fetch and is bounded [min, mrp] (client validation mirrors server). A **Discount**
control (type FLAT/PERCENT + value) sits in the Payment/Review step. A live **breakdown** (subtotal −
discount + GST-inclusive total, showing GST component) replaces the current simple total. `OrdersService`
create payload gains `discountType`/`discountValue`; the product picker (`CatalogService`) model gains
`minimumRate`,`mrp`,`wtMl` (salePrice already present) so the band can be shown/enforced.

**Order detail** drawer: show subtotal, discount (type+value+amount), GST, total; per-item shows Wt/ml.

## Data Models

```
products (+): minimum_rate DECIMAL(12,2) NULL, wt_ml VARCHAR(32) NULL
orders    (+): discount_type VARCHAR(10) NULL, discount_value DECIMAL(12,2) NULL
              (discount_amount already exists)
order_line_items: unchanged (gst_rate, hsn_code, rate, line_total already present)
```

## Error Handling

- Price-band violations (create/update product, order line rate) → `ValidationException` → HTTP 400 with a
  message naming the allowed range / offending value.
- Invalid discount (percent out of 0–100, flat negative or > subtotal) → `ValidationException` → 400.
- Invalid GST rate (not in {0,5,18}) → 400.
- Catalog seed is idempotent; re-runs are no-ops (Flyway version guard + `ON DUPLICATE KEY UPDATE`).
- Reads of deactivated products still resolve (no 404 for history).

## Correctness Properties

These invariants must hold for `OrderPricing.compute` and are the basis of the property tests:

1. **Discount conservation** — the sum of every line's `discountShare` equals the order `discount` amount
   exactly (no rupees created or lost by apportionment).
2. **GST aggregation** — the order `gstTotal` equals the sum of all per-line `gstAmount` values, and
   `gstByRate` values sum to `gstTotal`.
3. **GST extraction bound** — for any line, `0 ≤ gstAmount ≤ net` (the discounted, GST-inclusive line
   amount); a line with a null/zero GST rate has `gstAmount == 0`.
4. **Total identity** — `total == roundToWholeRupees(subtotal − discount)`; GST is contained within the
   total (price-inclusive, [D1]), never added on top.
5. **Discount validity** — PERCENT discounts resolve within `[0, subtotal]`; a FLAT discount never exceeds
   the subtotal and is never negative (else rejected before compute).
6. **Band enforcement** — a persisted order line's rate is always within `[minimum_rate, mrp]` of its
   product at creation time.

## Testing Strategy

- **Pure unit tests** for `OrderPricing`: subtotal, FLAT & PERCENT discount, proportional apportionment
  summing exactly, GST extraction per rate, mixed-rate aggregate, zero/legacy-rate lines, rounding to whole
  rupee. (jqwik property: Σ per-line gstAmount == gstTotal; Σ discountShare == discountAmount.)
- `ProductService` validation tests: band ordering + GST-set.
- `OrderService` tests: line rate below min / above mrp rejected; discount applied to total; response
  exposes subtotal/gst/discount; existing order-creation tests still pass.
- Migration sanity: V49/V50 apply on a scratch DB; the 30 products present & published, others hidden.
- Frontend `build:admin` clean; existing specs compile.
- Full backend `mvn clean test` (not incremental — enum-switch exhaustiveness gotcha) green.
```
