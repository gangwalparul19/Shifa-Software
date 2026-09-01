-- QuikShipX courier integration: one shipment row per order mirroring the
-- QuikShipX side. Created when an order is first published to QuikShipX
-- (create-order-v1 -> their "Pending" section); fills in the shipper order id,
-- mirrored status, AWB + courier + label URL (on allot-tracking-id-v1), and the
-- latest tracking status (track-order-v1). Additive; no backfill (existing
-- orders simply have no shipment row until they are re-published).
-- (QuikShipX integration)

CREATE TABLE order_shipments (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    order_id         BIGINT      NOT NULL,
    order_code       VARCHAR(40) NOT NULL,
    shipper_order_id VARCHAR(64) NULL,
    quikshipx_status VARCHAR(40) NULL,
    awb              VARCHAR(64) NULL,
    courier_id       VARCHAR(16) NULL,
    sub_courier_name VARCHAR(80) NULL,
    label_url        VARCHAR(1000) NULL,
    is_test          TINYINT(1)  NOT NULL DEFAULT 0,
    last_status_raw  VARCHAR(120) NULL,
    last_synced_at   DATETIME    NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_shipments_order_id UNIQUE (order_id),
    CONSTRAINT fk_order_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    KEY ix_order_shipments_awb (awb),
    KEY ix_order_shipments_order_code (order_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
