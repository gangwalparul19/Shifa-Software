# Implementation Plan

## Overview

Additive extension of the V48 product + order model: add price-band (`minimum_rate`) and `wt_ml` to
products, a typed order discount (FLAT/PERCENT), a pure order-pricing/GST domain (per-line extraction +
aggregate), band enforcement on order lines, catalog seed of the 30 official products (deactivating the
rest), and the matching product-admin + order-entry UI. Historical orders are untouched (they already
snapshot rate/GST/HSN per line).

## Tasks

- [x] 1. Product schema: price band + Wt/ml + order discount columns (backend)
  - Flyway `V49__product_price_band_and_wtml.sql`: `products.minimum_rate DECIMAL(12,2) NULL`,
    `products.wt_ml VARCHAR(32) NULL`, backfill `minimum_rate = sale_price` where null; add
    `orders.discount_type VARCHAR(10) NULL`, `orders.discount_value DECIMAL(12,2) NULL`.
  - Extend `Product` with `minimumRate` + `wtMl`; extend `OrderEntity` with `discountType` +
    `discountValue` (keep `discountAmount`).
  - _Requirements: 1.1, 1.4, 1.5, 4.3, 6.4_

- [x] 2. Product DTOs + service validation (backend)
  - Append `minimumRate` + `wtMl` to `ProductRequest`/`ProductResponse`; set in `ProductService.create/
    update`; validate `minimumRate ≤ salePrice ≤ mrp` and `gstRate ∈ {0,5,18}`; optional CSV columns.
  - _Requirements: 1.1, 1.2, 1.3, 1.6, 9.1, 9.2, 9.3_

- [x] 3. Pure order-pricing domain + tests (backend)
  - `order/domain/DiscountType` + `OrderPricing.compute(lines, discount)` (subtotal, apportioned discount,
    per-line GST extraction, aggregate gstTotal + gstByRate, whole-rupee total). Unit + jqwik property
    tests for the Correctness Properties.
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3, 8.4_

- [x] 4. Order creation: band enforcement + discount + GST wiring (backend)
  - Enforce per-line `[minimum_rate, mrp]` in `priceLines`; build `DiscountSpec` from the request; call
    `OrderPricing.compute`; persist discount fields + use computed total for classification. Append
    `discountType`/`discountValue` to `CreateOrderRequest` (thread through `LeadConvertRequest`).
  - _Requirements: 5.1, 5.2, 5.4, 6.1, 6.4, 6.6, 4.3, 7.1_

- [x] 5. Order response: subtotal / GST / discount exposure (backend)
  - Append `subtotalAmount`, `gstAmount`, `discountType`, `discountValue` to `OrderResponse` and
    `gstAmount` to `LineItemResponse`, computed at read time via `OrderPricing.compute`. Verify invoice
    reconciles.
  - _Requirements: 8.1, 8.2, 8.3, 5.3, 4.1, 4.2_

- [x] 6. Seed official catalog + deactivate the rest (backend)
  - Flyway `V50__seed_shifa_price_list.sql`: `INSERT ... ON DUPLICATE KEY UPDATE` the 30 products by
    deterministic SKU (`visibility='PUBLISHED'`); `UPDATE ... SET visibility='HIDDEN'` for the rest. No
    deletes; idempotent.
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4_

- [x] 7. Backend verification
  - `mvn -f "backend/pom.xml" clean test` (clean, not incremental) all green.
  - _Requirements: all backend_

- [x] 8. Frontend product management (Angular)
  - Product model/service/form gain `minimumRate` + `wtMl`; Pricing tab shows Min/Auto-Fetch/MRP + GST +
    HSN + Wt/ml with band validation; list shows Wt/ml.
  - _Requirements: 9.1, 9.2, 9.3_

- [x] 9. Frontend order entry: price band + discount + live GST (Angular)
  - `CatalogService` model gains `minimumRate`,`mrp`,`wtMl`; New Order line shows the band + Wt/ml, rate
    defaults to Auto-Fetch bounded [min,mrp]; Discount control (FLAT/PERCENT) + live breakdown; send
    `discountType`/`discountValue`; order detail shows subtotal/discount/GST/total + per-item Wt/ml.
  - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.6, 8.2, 10.1, 10.2, 10.3, 10.4_

- [x] 10. Frontend verification
  - `npm --prefix frontend run build:admin` clean; existing specs compile.
  - _Requirements: all frontend_

## Task Dependency Graph

```json
{
  "tasks": {
    "1": { "dependsOn": [] },
    "2": { "dependsOn": ["1"] },
    "3": { "dependsOn": [] },
    "4": { "dependsOn": ["2", "3"] },
    "5": { "dependsOn": ["3", "4"] },
    "6": { "dependsOn": ["1"] },
    "7": { "dependsOn": ["4", "5", "6"] },
    "8": { "dependsOn": ["2"] },
    "9": { "dependsOn": ["5"] },
    "10": { "dependsOn": ["8", "9"] }
  },
  "waves": [
    { "wave": 1, "tasks": ["1", "3"] },
    { "wave": 2, "tasks": ["2", "6"] },
    { "wave": 3, "tasks": ["4"] },
    { "wave": 4, "tasks": ["5"] },
    { "wave": 5, "tasks": ["7", "8", "9"] },
    { "wave": 6, "tasks": ["10"] }
  ]
}
```

- Task 1 (schema/entities) is the foundation for 2, 4, 6.
- Task 3 (pure pricing) is independent and feeds 4 and 5.
- Task 7 (backend verify) depends on 4, 5, 6.
- Tasks 8 and 9 (frontend) depend on backend DTOs (2 and 5); 10 verifies both.

## Notes

- Follow the Clock/dual-constructor + `@Autowired` conventions if any new service needs a Clock (none
  expected — `OrderPricing` is pure).
- Use `mvn clean` (not incremental) to catch enum-switch exhaustiveness gaps (known gotcha).
- Highest migration becomes **V50**; keep V49/V50 additive and idempotent; never edit an applied migration.
- Historical orders must remain unchanged — all pricing inputs (rate, gstRate, discount) are snapshotted.

