-- Buyer GSTIN on orders + seller aggregate turnover setting, for portal-ready
-- GSTR-1 supply classification (B2B / B2CL / B2CS) and HSN 4-vs-6-digit
-- enforcement. Additive/nullable, no backfill: existing orders keep NULL and
-- classify as B2CL/B2CS by rule; turnover defaults to the 4-digit HSN rule when
-- unset. (gst-filing-compliance spec, Requirements 1.1, 3.3, 14.2, 14.3)

ALTER TABLE orders
    ADD COLUMN buyer_gstin VARCHAR(15) NULL;

ALTER TABLE app_settings
    ADD COLUMN aggregate_turnover DECIMAL(15,2) NULL;
