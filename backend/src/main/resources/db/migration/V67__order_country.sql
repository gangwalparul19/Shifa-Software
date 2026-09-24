-- =============================================================================
-- V67 — Order destination country (India vs Outside India order entry).
--
-- The New Order form now supports orders shipping OUTSIDE India (a handful a
-- month). A domestic order keeps the structured city / state / 6-digit pincode;
-- an international order captures a single free-text address (stored in
-- `address_line`) and leaves city/state/postal_code empty.
--
-- `country` records the destination country for an international order. It is
-- NULL for a domestic (India) order — the historical default — so this is fully
-- additive/nullable and safe on existing data. The existing address columns
-- (address_line/city/state/postal_code) stay NOT NULL; international orders store
-- empty strings for the structured parts, which is DB-safe.
--
-- NOTE (GST): GST place-of-supply keys off the order's state. An international
-- order has a blank state, which the GST engine already treats as inter-state
-- (IGST). A dedicated export / zero-rated GST treatment is a possible follow-up;
-- this migration only records the destination.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN country VARCHAR(60) NULL AFTER postal_code;
