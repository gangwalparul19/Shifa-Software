-- =============================================================================
-- V82 — widen lead_source columns to fit the expanded LeadSource enum.
--
-- New LeadSource values were added (SHOPIFY_UPSELL, SHOPIFY_ABANDONMENT_SALE,
-- INBOUND_CALLS, REPEAT_CUSTOMER, REFERRAL). The longest, SHOPIFY_ABANDONMENT_SALE,
-- is 24 characters — longer than the original VARCHAR(20) on orders.lead_source
-- (V23) and leads.lead_source (V25). Saving an order/lead with such a value failed
-- with a data-truncation error, so the save silently did nothing.
--
-- Widen both columns to VARCHAR(40) (ample headroom for future values). Additive,
-- non-destructive; existing values are unaffected.
-- =============================================================================

ALTER TABLE orders MODIFY COLUMN lead_source VARCHAR(40) NULL;
ALTER TABLE leads  MODIFY COLUMN lead_source VARCHAR(40) NOT NULL;
