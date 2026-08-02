-- =============================================================================
-- V50 - Default HSN code for QuikShipX shipments
-- Spec: shopify-quikshipx-order-sync
-- =============================================================================
-- QuikShipX rejects a create-order whose product lines carry an HSN code shorter
-- than 2 characters ("HSN Code must be greater than 1 character"). Many Shifa
-- products have no HSN yet, and an order snapshots the (blank) HSN onto its line
-- at creation time, so the shipment payload sent a blank HSN and was rejected with
-- an HTTP 200 "status: failure" body.
--
-- This adds an admin-managed fallback HSN to the shipment defaults, used by
-- ShipmentPayloadFactory when neither the order line nor the product carries one.
-- It mirrors ship_default_category (V49). Additive and nullable; existing rows are
-- unaffected. Set it on the Settings page (Shipment Defaults) or by SQL.
-- =============================================================================

ALTER TABLE app_settings
    ADD COLUMN ship_default_hsn VARCHAR(20) NULL;
