-- V54: store the latest QuikShipX track-order response on the shipment so the order
-- drawer can show the full courier lifecycle timeline (Pending -> Confirmed -> Tracking
-- ID Assigned -> Label Printed -> ...) and the latest scan WITHOUT a live call or a trip
-- to the QuikShipX portal. Refreshed by the tracking poller. Additive/nullable.
ALTER TABLE order_shipments
    ADD COLUMN last_track_response TEXT NULL AFTER raw_acceptance;
