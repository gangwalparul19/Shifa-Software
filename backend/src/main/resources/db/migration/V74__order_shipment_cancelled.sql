-- =============================================================================
-- V74 — QuikShipX shipment "cancelled" marker (order-cancellation feature).
--
-- An admin can cancel an order even after it has been handed to the courier
-- (a QuikShipX tracking id / AWB was generated) — e.g. the payment never
-- arrived, or the customer cancels after a partial payment. When this happens
-- for a QuikShipX order, the OMS also requests cancellation at QuikShipX so the
-- courier is NOT sent to pick the parcel up.
--
-- `cancelled_at` records when the shipment was cancelled on our side. The
-- existing `quikshipx_status` column is set to 'Cancelled' at the same time
-- (no schema change needed for the status label). NULL for every non-cancelled /
-- pre-existing shipment, so this is fully additive and safe on existing data.
-- =============================================================================

ALTER TABLE order_shipments
    ADD COLUMN cancelled_at DATETIME NULL AFTER label_printed_at;
