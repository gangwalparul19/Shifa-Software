-- =============================================================================
-- Shifa Herbal Remedies OMS - Shopify ingestion + QuikShipX fulfilment (V49)
--
-- Spec: .kiro/specs/shopify-quikshipx-order-sync/
-- API contract: docs/QUIKSHIPX-API-V1.md
--
-- Makes Shifa OMS the single tracking surface across two order channels while
-- handing label generation and post-approval fulfilment status to QuikShipX:
--
--   * SHOPIFY_API  - placed on the Shopify storefront, ingested by webhook.
--   * SHIFA_ADMIN  - punched in the Shifa Admin Portal, published to QuikShipX.
--
-- ADDITIVE ONLY. Every new column is nullable or defaulted and every new table
-- is empty on arrival, so the pre-feature JAR runs unchanged against this schema
-- (rollback is a JAR swap). Engine/charset/money conventions follow V1.
--
-- The feature ships dark: app.shopify.enabled, app.quikshipx.enabled and
-- app.quikshipx.status-feed-available all default to false, so with no rows in
-- order_shipments every new predicate collapses to today's behaviour.
-- =============================================================================

-- ------------------------------------------------------------- orders --------

-- orders.source is VARCHAR(20) NOT NULL and already carries the OrderSource
-- name. SHOPIFY_API / SHIFA_ADMIN both fit, so no column change is needed.
-- Legacy SALESPERSON / STOREFRONT values are retained and folded to SHIFA_ADMIN
-- in code (OrderSource.canonical()); only genuinely blank rows are backfilled.
UPDATE orders SET source = 'SHIFA_ADMIN' WHERE source IS NULL OR source = '';

-- Shopify identifiers. shopify_order_id is the ingestion idempotency key: a
-- repeat webhook for the same Shopify order resolves to the existing row.
ALTER TABLE orders
    ADD COLUMN shopify_order_id     VARCHAR(64) NULL,
    ADD COLUMN shopify_order_number VARCHAR(40) NULL,
    -- Per-order escape hatch returning fulfilment authority to Shifa OMS:
    -- re-enables the internal label + packing queue for this order alone.
    ADD COLUMN fallback_mode        BOOLEAN     NOT NULL DEFAULT FALSE;

-- MySQL permits multiple NULLs in a unique index, so this enforces "at most one
-- Shifa order per Shopify order" without constraining pre-existing rows.
CREATE UNIQUE INDEX ux_orders_shopify_order_id ON orders (shopify_order_id);

-- Server-side channel filter on the Orders page.
CREATE INDEX ix_orders_source        ON orders (source);
CREATE INDEX ix_orders_fallback_mode ON orders (fallback_mode);

-- ---------------------------------------------------- order_shipments --------

-- The per-order QuikShipX shipment record. UNIQUE(order_id) is what makes
-- publication idempotence a database invariant rather than a code convention.
--
-- order_reference is the QuikShipX_Order_Reference we send as customer_order_id
-- (e.g. 'SHIFA-SHR-1001'). It is the ONLY identifier we control, and because the
-- create-order response body is undocumented it is also the reliable correlation
-- key -- quikshipx_shipment_id may never arrive, hence nullable.
CREATE TABLE order_shipments (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_id              BIGINT       NOT NULL,
    order_reference       VARCHAR(80)  NOT NULL,
    quikshipx_shipment_id VARCHAR(80)  NULL,
    awb                   VARCHAR(60)  NULL,
    courier_name          VARCHAR(120) NULL,
    tracking_url          VARCHAR(500) NULL,
    label_url             VARCHAR(500) NULL,
    last_status_token     VARCHAR(80)  NULL,
    last_status_at        DATETIME     NULL,
    -- Booked with the TEST secret, so it lives in QuikShipX's Test section and
    -- must never be mistaken for a live shipment in the admin UI.
    is_test               BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The create-order response exactly as received. Retained so the real
    -- identifier key names can be pinned from live traffic.
    raw_acceptance        TEXT         NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                       ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_order_shipments PRIMARY KEY (id),
    CONSTRAINT ux_order_shipments_order     UNIQUE (order_id),
    CONSTRAINT ux_order_shipments_reference UNIQUE (order_reference),
    CONSTRAINT ux_order_shipments_shipment  UNIQUE (quikshipx_shipment_id),
    CONSTRAINT fk_order_shipments_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_order_shipments_awb ON order_shipments (awb);

-- ------------------------------------------------- integration_events --------

-- Every inbound Shopify / QuikShipX delivery, plus every outbound publication
-- failure. UNIQUE(source, external_event_id) is SCOPED rather than global, so
-- the two providers cannot collide on an id, and it is what makes duplicate
-- webhook deliveries a no-op.
CREATE TABLE integration_events (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    source            VARCHAR(20)   NOT NULL,
    external_event_id VARCHAR(180)  NOT NULL,
    event_topic       VARCHAR(80)   NULL,
    raw_payload       LONGTEXT      NULL,
    received_at       DATETIME      NOT NULL,
    outcome           VARCHAR(30)   NOT NULL,
    failure_reason    VARCHAR(1000) NULL,
    attempt_count     INT           NOT NULL DEFAULT 0,
    order_id          BIGINT        NULL,
    status_token      VARCHAR(80)   NULL,
    processed_at      DATETIME      NULL,
    replayed_at       DATETIME      NULL,
    replayed_by       VARCHAR(150)  NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_integration_events PRIMARY KEY (id),
    CONSTRAINT ux_integration_events_ext UNIQUE (source, external_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_integration_events_outcome  ON integration_events (outcome, received_at);
CREATE INDEX ix_integration_events_order    ON integration_events (order_id);
CREATE INDEX ix_integration_events_received ON integration_events (received_at);

-- ------------------------------------------------ order_review_reasons -------

-- Why an ingested Shopify order needs a human look. A child table rather than a
-- CSV column so UNIQUE(order_id, reason) enforces "record each reason exactly
-- once" even under concurrent re-processing, and so the review queue is an
-- indexed join. Stays empty while Shopify ingestion is disabled.
CREATE TABLE order_review_reasons (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_id   BIGINT       NOT NULL,
    reason     VARCHAR(40)  NOT NULL,
    detail     VARCHAR(255) NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_order_review_reasons PRIMARY KEY (id),
    CONSTRAINT ux_order_review_reasons UNIQUE (order_id, reason),
    CONSTRAINT fk_order_review_reasons_order FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------------------------ quikshipx_status_map -------

-- QuikShipX status token -> Shifa OrderStatus, held as DATA so an unrecognised
-- token is an UNMAPPED_STATUS outcome plus an admin alert, never a deploy.
--
-- NOTE: only LABEL_PRINTED and READY_FOR_PICKUP are confirmed (verbally, from
-- the client's description of the packing workflow). The rest are the default
-- mapping the spec mandates and are ASSUMED pending QuikShipX's real vocabulary.
-- Corrections ship as V50 INSERT ... ON DUPLICATE KEY UPDATE, never by editing
-- this file. This table is only consulted once a status feed exists.
CREATE TABLE quikshipx_status_map (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    status_token VARCHAR(80)  NOT NULL,
    order_status VARCHAR(40)  NOT NULL,
    description  VARCHAR(255) NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_quikshipx_status_map PRIMARY KEY (id),
    CONSTRAINT ux_quikshipx_status_map_token UNIQUE (status_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO quikshipx_status_map (status_token, order_status, description, sort_order) VALUES
  ('LABEL_PRINTED',    'LABEL_GENERATED',   'Label printed in the QuikShipX portal (CONFIRMED)', 10),
  ('READY_FOR_PICKUP', 'PACKED',            'Packer marked ready for pickup (CONFIRMED)',        20),
  ('PICKED_UP',        'DISPATCHED',        'Pickup completed by the courier (ASSUMED)',         30),
  ('PICKUP_DONE',      'DISPATCHED',        'Alias for pickup completed (ASSUMED)',              31),
  ('IN_TRANSIT',       'IN_TRANSIT',        'Shipment moving through the network (ASSUMED)',      40),
  ('OUT_FOR_DELIVERY', 'OUT_FOR_DELIVERY',  'With the delivery agent (ASSUMED)',                 50),
  ('DELIVERED',        'DELIVERED',         'Delivered to the customer (ASSUMED)',               60),
  ('RTO',              'RTO',               'Return to origin (ASSUMED)',                        70),
  ('RTO_INITIATED',    'RTO',               'Alias for return to origin (ASSUMED)',              71),
  ('CUSTOMER_REFUSED', 'CUSTOMER_REJECTED', 'Customer refused the shipment (ASSUMED)',           80),
  ('REFUSED',          'CUSTOMER_REJECTED', 'Alias for customer refusal (ASSUMED)',              81);

-- ------------------------------------- app_settings: Shipment_Defaults -------

-- The QuikShipX create-order body requires parcel logistics Shifa OMS has never
-- stored. Rather than a new table + a new page, these extend the EXISTING
-- single-row app_settings (id = 1) and the existing ADMIN Settings surface.
--
-- ship_pickup_warehouse_id is deliberately NULL: it comes from the QuikShipX
-- dashboard and publication is blocked with an admin alert until it is set,
-- rather than sending a body QuikShipX would reject.
ALTER TABLE app_settings
    ADD COLUMN ship_pickup_warehouse_id VARCHAR(40)   NULL,
    -- '1' Flyer, '2' Cardboard.
    ADD COLUMN ship_package_type        VARCHAR(1)    NOT NULL DEFAULT '1',
    -- '1' SURFACE, '2' EXPRESS.
    ADD COLUMN ship_shipping_mode       VARCHAR(1)    NOT NULL DEFAULT '1',
    ADD COLUMN ship_dead_weight_grams   INT           NOT NULL DEFAULT 500,
    ADD COLUMN ship_length_cm           INT           NOT NULL DEFAULT 10,
    ADD COLUMN ship_width_cm            INT           NOT NULL DEFAULT 10,
    ADD COLUMN ship_height_cm           INT           NOT NULL DEFAULT 10,
    -- Delivery charge levied by the seller; 0.00 when delivery is free.
    ADD COLUMN ship_shipping_amount     DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    -- Fallback for product_category when a product has no category.
    ADD COLUMN ship_default_category    VARCHAR(120)  NULL;

-- ------------------------------------------ products.dead_weight_grams -------

-- Optional per-product dead weight. When present, the submitted parcel weight is
-- SUM(product weight * line quantity) across the order; NULL falls back to the
-- settings-level ship_dead_weight_grams.
ALTER TABLE products ADD COLUMN dead_weight_grams INT NULL;
