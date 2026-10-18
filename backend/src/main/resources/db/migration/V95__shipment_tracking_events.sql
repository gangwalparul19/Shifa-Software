-- Persisted QuikShipX courier scan timeline per order (Option B: store the
-- courier scan events in our DB so the public /track-order page can render the
-- detailed timeline — status + location + instruction + scan time — WITHOUT a
-- live QuikShipX API call on each anonymous visit, saving cost and keeping the
-- page fast and abuse-resistant).
--
-- The rows mirror QuikShipX's `track-order-v1` `shipment_scanning` entries. The
-- whole set for an order is REPLACED on each successful track (the QuikShipX
-- response is the full timeline), refreshed by the scheduled courier poller and
-- by the admin live-tracking view. Keyed by order_id; newest-first ordering is
-- by scan_at (raw ISO-ish string sorts lexically) with sort_order as a stable
-- tiebreaker for same-timestamp scans. Additive; no backfill (events populate on
-- the next track cycle). (public order tracking — detailed courier timeline)

CREATE TABLE shipment_tracking_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    status       VARCHAR(120) NULL,
    location     VARCHAR(255) NULL,
    instructions VARCHAR(500) NULL,
    scan_at      VARCHAR(40)  NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    synced_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_shipment_tracking_events_order FOREIGN KEY (order_id) REFERENCES orders (id),
    KEY ix_shipment_tracking_events_order (order_id, sort_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
