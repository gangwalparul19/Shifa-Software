-- =============================================================================
-- Shifa Herbal Remedies OMS - order lead source + customer email (V23)
--
-- Role-Based Order Workflow, data model (design §3.1, §3.4).
--
--  * lead_source      - the origin channel of the lead (LeadSource enum),
--                       distinct from orders.source (OrderSource). Required at
--                       the service/DTO layer for new salesperson orders
--                       (Req 4.1-4.3); NULL at the column level so pre-existing
--                       seeded V22 rows stay valid and report as UNSPECIFIED.
--  * lead_source_note - optional free text, meaningful only when
--                       lead_source = OTHER (Req 4.5); <= 200 chars at the DTO.
--  * customer_email   - customer email for milestone emails (Req 7.2, 10.7,
--                       11.4); when absent the email channel is skipped.
--
-- All columns are additive and nullable, so this is safe on the seeded V22
-- dataset with no backfill. Never edit an applied migration; V22 is the
-- previous highest version. Conventions match V1 (utf8mb4).
-- =============================================================================

ALTER TABLE orders ADD COLUMN lead_source      VARCHAR(20)  NULL AFTER source;
ALTER TABLE orders ADD COLUMN lead_source_note VARCHAR(200) NULL AFTER lead_source;
ALTER TABLE orders ADD COLUMN customer_email   VARCHAR(150) NULL AFTER customer_mobile;

-- Backs report grouping by lead source over date ranges (Req 16.1).
CREATE INDEX ix_orders_lead_source ON orders (lead_source);
