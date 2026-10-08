-- Web push subscriptions (FEATURE-ROADMAP §8.3).
--
-- Each row is a browser Push API subscription for a staff user: the endpoint URL
-- plus the two client keys (p256dh, auth) needed to encrypt a VAPID push. Sending
-- is gated on VAPID keys being configured server-side; when unset the app simply
-- never sends (subscriptions are still stored harmlessly).
CREATE TABLE push_subscriptions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    endpoint    VARCHAR(500) NOT NULL,
    p256dh      VARCHAR(255) NOT NULL,
    auth_secret VARCHAR(255) NOT NULL,
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_push_subscriptions_endpoint (endpoint),
    KEY ix_push_subscriptions_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
