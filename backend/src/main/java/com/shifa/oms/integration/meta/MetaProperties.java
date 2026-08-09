package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.WebhookPayloadLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for Meta (Facebook/Instagram) Lead Ads ingestion, bound from
 * {@code app.meta.*} (spec {@code meta-lead-sync}, Requirements 2, 5, 8, 9).
 *
 * <p>Mirrors {@link com.shifa.oms.integration.shopify.ShopifyProperties}: the
 * feature is OFF by default, deliveries are always stored, and the {@code enabled}
 * flag gates <em>lead capture</em> rather than <em>receipt</em>.
 *
 * @param enabled             master switch; OFF stores deliveries but captures no lead (Req 9)
 * @param appSecret           Meta App secret; Meta sends a HEX HMAC-SHA256 in
 *                            {@code X-Hub-Signature-256} as {@code sha256=<hex>} (Req 2)
 * @param pageAccessToken     long-lived (system-user) Page token for Graph API calls (Req 5)
 * @param verifyToken         shared secret echoed during the GET verification handshake (Req 1)
 * @param pageId              the business Page id; falls back to the local default (Req 9.2)
 * @param graphApiBaseUrl     Graph API host
 * @param graphApiVersion     Graph API version path segment (e.g. {@code v21.0})
 * @param requestTimeout      Graph call timeout (Req 5.5)
 * @param maxPayloadBytes     largest accepted webhook body, checked before hashing
 * @param maxAttempts         total ingestion attempts including the first (1 + 3 retries)
 * @param retryBackoff        first-retry delay; the ladder doubles from here
 * @param retryMaxBackoff     ceiling so a long-failing event still retries usefully
 * @param ingestDrainIntervalMs drainer poll interval in milliseconds
 */
@ConfigurationProperties(prefix = "app.meta")
public record MetaProperties(
        Boolean enabled,
        String appSecret,
        String pageAccessToken,
        String verifyToken,
        String pageId,
        String graphApiBaseUrl,
        String graphApiVersion,
        Duration requestTimeout,
        Integer maxPayloadBytes,
        Integer maxAttempts,
        Duration retryBackoff,
        Duration retryMaxBackoff,
        Long ingestDrainIntervalMs,
        String mode) {

    /** The header carrying Meta's hex HMAC-SHA256 of the raw body, as {@code sha256=<hex>}. */
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    /** Graph client backend: {@code MOCK} (local, no network) or {@code HTTP} (live Graph API). */
    public static final String MODE_MOCK = "MOCK";
    public static final String MODE_HTTP = "HTTP";

    /** The local default Page id used when {@code app.meta.page-id} is not configured (Req 9.2). */
    public static final String DEFAULT_PAGE_ID = "105357624712950";

    public MetaProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (graphApiBaseUrl == null || graphApiBaseUrl.isBlank()) {
            graphApiBaseUrl = "https://graph.facebook.com";
        }
        if (graphApiVersion == null || graphApiVersion.isBlank()) {
            graphApiVersion = "v21.0";
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            requestTimeout = Duration.ofSeconds(10);
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
        if (ingestDrainIntervalMs == null || ingestDrainIntervalMs < 1000) {
            ingestDrainIntervalMs = 15000L;
        }
        if (mode == null || mode.isBlank()) {
            mode = MODE_MOCK;
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Whether the offline mock Graph client is active (no network, deterministic). */
    public boolean isMockMode() {
        return !MODE_HTTP.equalsIgnoreCase(mode);
    }

    /** Whether an App secret is configured. Without one, webhook deliveries are rejected. */
    public boolean hasAppSecret() {
        return appSecret != null && !appSecret.isBlank();
    }

    public boolean hasPageAccessToken() {
        return pageAccessToken != null && !pageAccessToken.isBlank();
    }

    public boolean hasVerifyToken() {
        return verifyToken != null && !verifyToken.isBlank();
    }

    /** The configured Page id, or the local default when unset (Req 9.2). */
    public String resolvedPageId() {
        return (pageId == null || pageId.isBlank()) ? DEFAULT_PAGE_ID : pageId.trim();
    }

    /**
     * Builds a versioned Graph API URL for a node/edge, e.g.
     * {@code graphUrl("1234567890")} → {@code https://graph.facebook.com/v21.0/1234567890}.
     */
    public String graphUrl(String pathSegment) {
        String base = graphApiBaseUrl.endsWith("/")
                ? graphApiBaseUrl.substring(0, graphApiBaseUrl.length() - 1)
                : graphApiBaseUrl;
        return base + "/" + graphApiVersion + "/" + pathSegment;
    }
}
