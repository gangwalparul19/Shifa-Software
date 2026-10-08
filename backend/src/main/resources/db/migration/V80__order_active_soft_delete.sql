-- =============================================================================
-- V80 — Soft-delete flag for orders (admin delete-order feature).
--
-- Replaces the fragile hard-delete (which failed on FK constraints, e.g. the
-- QuikShipX order_shipments FK) with a reversible soft delete: an admin
-- "delete" sets orders.active = FALSE instead of removing the row. Every
-- entity-based read excludes inactive orders via Hibernate @SQLRestriction on
-- OrderEntity, and the native aggregate queries carry an explicit
-- `AND o.active = 1` clause — so a deleted order disappears everywhere
-- (orders list, dashboards, reports, P&L) while its row + children + audit
-- trail stay intact.
--
-- Additive and backfill-safe: every existing order becomes active (TRUE) so
-- nothing changes for live data. Indexed because nearly every order query now
-- filters on active.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX ix_orders_active ON orders (active);
