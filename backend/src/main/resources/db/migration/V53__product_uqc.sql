-- Product UQC (Unit Quantity Code) for the GSTR-1 HSN summary (Table 12).
-- Additive/nullable; safe on seeded data. The default UQC "NOS" is applied in
-- code (Uqc.resolve), NOT as a DB default, so legacy rows need no backfill and
-- the HSN summary always resolves a valid UQC.
-- (gst-filing-compliance spec, Requirements 3.2, 14.2)

ALTER TABLE products
    ADD COLUMN uqc VARCHAR(10) NULL AFTER wt_ml;
