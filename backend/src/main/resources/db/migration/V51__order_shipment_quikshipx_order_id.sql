-- =============================================================================
-- V51 - Store QuikShipX's own order id on the shipment record
-- Spec: shopify-quikshipx-order-sync
-- =============================================================================
-- QuikShipX's create-order response carries TWO identifiers, e.g.
--   {"response":[{"id":65580852235,"status":"success","order_id":177286}]}
-- We already stored `id` as quikshipx_shipment_id, but `order_id` (177286) -- the
-- number to quote when tracking the order in the QuikShipX portal -- was not kept.
-- This adds a dedicated column for it and backfills existing rows from the raw
-- response we retained. Additive and nullable; the AWB still arrives later (the
-- create response does not include one).
-- =============================================================================

ALTER TABLE order_shipments
    ADD COLUMN quikshipx_order_id VARCHAR(80) NULL AFTER quikshipx_shipment_id;

-- Backfill from the verbatim response stored on each row. JSON_EXTRACT returns the
-- numeric order_id; JSON_UNQUOTE + CAST leaves a clean string, and a row whose
-- response has no order_id (or is not JSON) simply stays NULL.
UPDATE order_shipments
SET quikshipx_order_id = JSON_UNQUOTE(JSON_EXTRACT(raw_acceptance, '$.response[0].order_id'))
WHERE quikshipx_order_id IS NULL
  AND raw_acceptance IS NOT NULL
  AND JSON_VALID(raw_acceptance)
  AND JSON_EXTRACT(raw_acceptance, '$.response[0].order_id') IS NOT NULL;
