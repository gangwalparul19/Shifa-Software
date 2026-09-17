-- In-house delivery: record an optional vehicle / transport reference for the
-- leg of the journey Shifa's own team arranges (bus operator, train, taxi, own
-- van, ...). An in-house order has no courier AWB, so this — together with the
-- existing handover_name/handover_phone (who the parcel was physically handed
-- to) — is what identifies the shipment in the real world.
--
-- Additive & nullable: existing rows and every QuikShipX/courier order simply
-- leave it NULL.
ALTER TABLE orders
    ADD COLUMN vehicle_number VARCHAR(40) NULL;
