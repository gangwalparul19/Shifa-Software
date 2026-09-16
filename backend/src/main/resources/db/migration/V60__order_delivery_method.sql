-- =============================================================================
-- Per-order delivery method (QuikShipX vs in-house/manual delivery).
--
-- Today every order that is punched (and later approved) is unconditionally
-- published to QuikShipX whenever the integration is enabled — there is no way
-- for an individual order to opt out of the courier partner and be delivered
-- in-house. This adds a per-order marker so a salesperson/admin can flag an
-- order for in-house delivery at entry time; the QuikShipX publish/confirm/allot
-- pipeline (OrderService/AdminOrderService/QuikShipXService) is gated on this
-- column in addition to the existing global app.quikshipx.enabled flag.
--
-- Default 'QUIKSHIPX' preserves today's behaviour for every existing and future
-- order that does not explicitly opt out. Additive/nullable-safe (has a
-- default), so no backfill is required.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN delivery_method VARCHAR(20) NOT NULL DEFAULT 'QUIKSHIPX' AFTER source;

CREATE INDEX ix_orders_delivery_method ON orders (delivery_method);
