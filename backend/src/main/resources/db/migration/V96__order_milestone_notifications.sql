-- Per-order WhatsApp milestone de-duplication (customer shipment updates).
--
-- The customer gets a WhatsApp message at a few shipment milestones (order
-- placed, picked up, first "vehicle departed", out for delivery). The courier
-- scan timeline is re-polled every 5 minutes and a milestone like "Vehicle
-- Departed" can appear 2-3 times, so we must fire each milestone EXACTLY ONCE.
-- This table records which milestones have already been notified for an order;
-- a UNIQUE(order_id, milestone) lets the notifier atomically "claim" a milestone
-- (insert-if-absent) before enqueuing the message, so a concurrent/repeat poll
-- cycle can never send a duplicate. Additive. (customer shipment notifications)

CREATE TABLE order_milestone_notifications (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_id   BIGINT      NOT NULL,
    milestone  VARCHAR(40) NOT NULL,
    notified_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_milestone UNIQUE (order_id, milestone),
    CONSTRAINT fk_order_milestone_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
