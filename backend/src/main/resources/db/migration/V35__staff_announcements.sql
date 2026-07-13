-- Staff announcement banners (FEATURE-ROADMAP §8.4).
--
-- An admin posts a short notice (policy change, target push, downtime warning)
-- that every signed-in staff member sees as a banner across the app. Active
-- announcements are shown; deactivating one hides it for everyone. Severity maps
-- to the same info/success/warning/danger palette used by notifications.
CREATE TABLE staff_announcements (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    message         VARCHAR(500) NOT NULL,
    severity        VARCHAR(20)  NOT NULL DEFAULT 'info',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      BIGINT       NULL,
    created_by_name VARCHAR(150) NULL,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NULL,
    PRIMARY KEY (id),
    KEY ix_staff_announcements_active (active, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
