-- =============================================================================
-- Shifa Herbal Remedies OMS - Admin notifications center (V16)
--
-- "Operations depth" Feature 2: persist admin-facing alerts so there is a
-- durable history with read/unread state. Today admin alerts are ephemeral
-- (relayed over SSE only); this table records one row per admin notification
-- event (ORDER_PACKED, ORDER_STATUS_CHANGED, CLAIM_FILED_REQUIRED,
-- COURIER_ASSIGN_FAILED, WHATSAPP_FAILED, BACKUP_FAILED, LOW_STOCK) as it is
-- published, independent of whether an admin is connected. The SSE contract is
-- unchanged; this is an additive, best-effort side record.
--
-- source_event_id references the originating outbox row (nullable) so a
-- notification can be traced back and de-duplicated at the write site.
--
-- Engine / charset conventions match V1 (InnoDB, utf8mb4). Additive only.
-- =============================================================================

CREATE TABLE admin_notifications (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    type            VARCHAR(40)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    detail          TEXT         NULL,
    severity        VARCHAR(20)  NOT NULL DEFAULT 'info',
    order_id        BIGINT       NULL,
    order_code      VARCHAR(30)  NULL,
    source_event_id BIGINT       NULL,
    read_flag       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME     NOT NULL,
    read_at         DATETIME     NULL,
    CONSTRAINT pk_admin_notifications PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The unread badge/list is the hot path: "unread first, newest first".
CREATE INDEX ix_admin_notifications_read_created ON admin_notifications (read_flag, created_at);

-- De-duplication guard: at most one notification per originating outbox event.
-- MySQL permits multiple NULLs under a UNIQUE index, so manually-recorded
-- notifications (no source event) are unaffected.
CREATE UNIQUE INDEX ux_admin_notifications_source_event ON admin_notifications (source_event_id);
