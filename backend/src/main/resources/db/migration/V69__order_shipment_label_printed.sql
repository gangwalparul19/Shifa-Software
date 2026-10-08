-- =============================================================================
-- V69 — QuikShipX shipment "label printed" marker.
--
-- The Packaging "Print Labels" section lists Shopify orders that reached
-- QuikShipX "Tracking ID Assigned" (order status Courier_Assigned) so the packing
-- team can print the QuikShipX shipping label (single or multi-select). Once
-- printed, the order moves from the "to print" list into a "label printed" list.
--
-- `label_printed_at` records when the QuikShipX label was printed. It is an
-- ADDITIVE marker only — it does NOT change the order's lifecycle status (the
-- order stays Courier_Assigned so the QuikShipX tracking poll keeps driving it).
-- NULL for every not-yet-printed / pre-existing shipment, so this is fully
-- additive and safe on existing data.
-- =============================================================================

ALTER TABLE order_shipments
    ADD COLUMN label_printed_at DATETIME NULL AFTER label_url;
