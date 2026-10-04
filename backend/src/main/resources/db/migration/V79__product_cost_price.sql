-- =============================================================================
-- V79 — Product cost price for margin analytics (ENHANCEMENT 3.6).
--
-- The channel dashboard shows per-channel REVENUE but not margin, because no
-- product cost was ever recorded. This adds an optional per-product cost price
-- (what Shifa pays to source one unit) so the multi-channel revenue-attribution
-- view can estimate COGS and therefore gross margin per channel.
--
-- Nullable + additive: existing products have no cost until an admin sets one.
-- Lines whose product has no cost contribute zero COGS and the margin view flags
-- the result as based on partial cost data, so a missing cost never silently
-- overstates margin.
-- =============================================================================
ALTER TABLE products
    ADD COLUMN cost_price DECIMAL(12,2) NULL AFTER sale_price;
