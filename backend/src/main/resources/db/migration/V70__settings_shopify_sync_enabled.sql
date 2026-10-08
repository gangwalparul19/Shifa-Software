-- Shopify integration on/off switch (admin-controlled from the Shopify Sync page).
-- When OFF, the Shopify orders/create webhook is still signature-checked and
-- acknowledged (HTTP 200, so Shopify does not retry or disable the webhook) but
-- the order is NOT imported. Defaults to OFF: the integration is in testing and
-- must stay paused until an admin switches it on.
ALTER TABLE app_settings
    ADD COLUMN shopify_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;
