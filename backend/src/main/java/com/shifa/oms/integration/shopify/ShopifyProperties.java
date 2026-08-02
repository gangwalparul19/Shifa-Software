package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.WebhookPayloadLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for Shopify order ingestion, bound from {@code app.shopify.*}
 * (spec {@code shopify-quikshipx-order-sync}, Requirements 2, 3, 15).
 *
 * @param enabled                   master switch. OFF by default, so the webhook records
 *                                  deliveries for inspection but creates no order until
 *                                  the client confirms the app setup
 * @param webhookSecret             the Shopify app's webhook signing secret. Shopify sends
 *                                  a <b>base64</b> HMAC-SHA256 in {@code X-Shopify-Hmac-Sha256}
 * @param shopDomain                the shop domain, for log and audit context only
 * @param maxPayloadBytes           largest accepted webhook body (Req 2.8)
 * @param maxAttempts               total ingestion attempts including the first (1 + 3 retries)
 * @param retryBackoff              first-retry delay; the ladder doubles from here
 * @param retryMaxBackoff           ceiling, so a long-failing event still retries usefully
 * @param suppressCustomerMessaging whether to skip customer WhatsApp/email for Shopify
 *                                  orders. Default true, because Shopify already emails the
 *                                  buyer and double-messaging is worse than silence (Req 15.2)
 */
@ConfigurationProperties(prefix = "app.shopify")
public record ShopifyProperties(
        Boolean enabled,
        String webhookSecret,
        String shopDomain,
        Integer maxPayloadBytes,
        Integer maxAttempts,
        Duration retryBackoff,
        Duration retryMaxBackoff,
        Boolean suppressCustomerMessaging) {

    /** The header carrying Shopify's base64 HMAC-SHA256 of the raw body. */
    public static final String SIGNATURE_HEADER = "X-Shopify-Hmac-Sha256";

    /** The header carrying Shopify's unique delivery id, used for duplicate detection. */
    public static final String EVENT_ID_HEADER = "X-Shopify-Webhook-Id";

    /** The header carrying the event topic, e.g. {@code orders/create}. */
    public static final String TOPIC_HEADER = "X-Shopify-Topic";

    public ShopifyProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (maxPayloadBytes == null) {
            maxPayloadBytes = WebhookPayloadLimit.DEFAULT_MAX_BYTES;
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 4;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
        if (retryMaxBackoff == null) {
            retryMaxBackoff = Duration.ofMinutes(15);
        }
        if (suppressCustomerMessaging == null) {
            suppressCustomerMessaging = Boolean.TRUE;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isSuppressCustomerMessaging() {
        return Boolean.TRUE.equals(suppressCustomerMessaging);
    }

    /** Whether a signing secret is configured. Without one, deliveries are rejected. */
    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
