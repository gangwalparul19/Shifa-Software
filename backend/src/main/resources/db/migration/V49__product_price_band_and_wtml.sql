-- Product price band (minimum rate) + weight/volume descriptor, and typed
-- order-level discount. Additive/nullable; safe on seeded data.
-- (product-catalog-pricing-gst spec, Requirements 1.1, 1.4, 1.5, 4.3, 6.4)

ALTER TABLE products
    ADD COLUMN minimum_rate DECIMAL(12,2) NULL AFTER sale_price,
    ADD COLUMN wt_ml VARCHAR(32) NULL AFTER hsn_code;

-- Backfill the minimum to the current sale price so existing products have a
-- sensible floor (Req 1.5). Admins can lower it later.
UPDATE products SET minimum_rate = sale_price WHERE minimum_rate IS NULL;

-- Order-level discount: type (FLAT | PERCENT) + the raw entered value. The
-- resolved rupee reduction continues to live in the existing discount_amount.
ALTER TABLE orders
    ADD COLUMN discount_type VARCHAR(10) NULL AFTER discount_amount,
    ADD COLUMN discount_value DECIMAL(12,2) NULL AFTER discount_type;
