package com.shifa.oms.quikshipx;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the QuikShipX courier integration, bound from
 * {@code app.quikshipx.*} (contract: the client-supplied QuikShipX API v1 —
 * create-order, allot-tracking-id, track-order).
 *
 * <p>Three things about this integration shape the properties:
 * <ul>
 *   <li><b>Credentials go in the request body</b>, not a header. QuikShipX takes
 *       {@code client_code}, {@code user_id} and {@code user_secret} inside
 *       {@code shipper_details}, so there is no API-key header.</li>
 *   <li><b>Test versus live is chosen by secret</b>, not by URL. A TEST secret
 *       files the order under QuikShipX's Test section; a LIVE secret under
 *       Pending. Both secrets are configured; {@link #secretMode()} selects which
 *       one is sent and stamps {@code order_shipments.is_test}.</li>
 *   <li><b>The whole integration is OFF by default</b> ({@link #enabled()} =
 *       false), so the internal label + packing flow behaves exactly as before
 *       until an admin supplies the credentials and turns it on.</li>
 * </ul>
 *
 * @param enabled          master switch; OFF by default
 * @param mode             client backend: {@code MOCK} (default, deterministic, no
 *                         network) or {@code HTTP} (real API)
 * @param baseUrl          API host; operations are {@code POST {baseUrl}/api/...-v1}
 * @param clientCode       {@code shipper_details.client_code}
 * @param userId           {@code shipper_details.user_id}
 * @param testSecret       the TEST {@code user_secret} (books into the Test section)
 * @param liveSecret       the LIVE {@code user_secret} (books a real parcel)
 * @param secretMode       {@code TEST} (default) or {@code LIVE}; picks which secret
 *                         is sent and stamps {@code order_shipments.is_test}
 * @param warehouseId      {@code shipment_pickup_warehouse_id}
 * @param courierId        {@code courier_id} used when allotting a tracking id
 * @param packageType      default {@code shipment_package_type} (1 Flyer, 2 Cardboard)
 * @param shippingMode     default {@code shipment_shipping_mode} (1 Surface, 2 Express)
 * @param deadWeightGrams  default {@code shipment_dead_weight_in_grams}
 * @param lengthCm         default {@code shipment_length} (cm)
 * @param widthCm          default {@code shipment_width} (cm)
 * @param heightCm         default {@code shipment_height} (cm)
 * @param shippingAmount   default {@code shipping_amount} (seller delivery charge)
 * @param productCategory  default {@code product_category} sent per line
 * @param confirmOrderPath optional path for a confirm-order call once QuikShipX
 *                         documents one; blank means "mirror status locally only"
 * @param requestTimeout   per-call timeout
 * @param maxAttempts      total create/confirm attempts including the first
 * @param retryBackoff     delay between create/confirm retries
 */
@ConfigurationProperties(prefix = "app.quikshipx")
public record QuikShipXProperties(
        Boolean enabled,
        String mode,
        String baseUrl,
        String clientCode,
        String userId,
        String testSecret,
        String liveSecret,
        String secretMode,
        String warehouseId,
        String courierId,
        String packageType,
        String shippingMode,
        String deadWeightGrams,
        String lengthCm,
        String widthCm,
        String heightCm,
        String shippingAmount,
        String productCategory,
        String confirmOrderPath,
        Duration requestTimeout,
        Integer maxAttempts,
        Duration retryBackoff) {

    /** The create-order path, appended to {@link #baseUrl()}. */
    public static final String CREATE_ORDER_PATH = "/api/create-order-v1";
    /** The allot-tracking-id path, appended to {@link #baseUrl()}. */
    public static final String ALLOT_TRACKING_PATH = "/api/allot-tracking-id-v1";
    /** The track-order path, appended to {@link #baseUrl()}. */
    public static final String TRACK_ORDER_PATH = "/api/track-order-v1";

    public QuikShipXProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (mode == null || mode.isBlank()) {
            mode = "MOCK";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://head.quikshipx.com";
        }
        // Strip a trailing slash so path concatenation is clean.
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (secretMode == null || secretMode.isBlank()) {
            // Default to TEST so a misconfiguration books into the Test section
            // rather than dispatching a real parcel.
            secretMode = "TEST";
        }
        if (courierId == null || courierId.isBlank()) {
            courierId = "1";
        }
        if (packageType == null || packageType.isBlank()) {
            packageType = "2"; // Cardboard box (herbal bottles ship in boxes).
        }
        if (shippingMode == null || shippingMode.isBlank()) {
            shippingMode = "1"; // Surface.
        }
        if (deadWeightGrams == null || deadWeightGrams.isBlank()) {
            deadWeightGrams = "500";
        }
        if (lengthCm == null || lengthCm.isBlank()) {
            lengthCm = "15";
        }
        if (widthCm == null || widthCm.isBlank()) {
            widthCm = "15";
        }
        if (heightCm == null || heightCm.isBlank()) {
            heightCm = "15";
        }
        if (shippingAmount == null || shippingAmount.isBlank()) {
            shippingAmount = "0";
        }
        if (productCategory == null || productCategory.isBlank()) {
            productCategory = "Herbal";
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 5;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
    }

    /** Whether the integration is switched on. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** Whether the live client backend ({@code HTTP}) is selected. */
    public boolean isHttp() {
        return "HTTP".equalsIgnoreCase(mode);
    }

    /** Whether the TEST secret is in use (stamped onto each shipment). */
    public boolean isTestSecret() {
        return !"LIVE".equalsIgnoreCase(secretMode);
    }

    /** The {@code user_secret} to send, per {@link #secretMode()}. */
    public String activeSecret() {
        return isTestSecret() ? testSecret : liveSecret;
    }

    /** Whether client code, user id, and the active secret are all present. */
    public boolean hasCredentials() {
        return isNotBlank(clientCode) && isNotBlank(userId) && isNotBlank(activeSecret());
    }

    public String createOrderUrl() {
        return baseUrl + CREATE_ORDER_PATH;
    }

    public String allotTrackingUrl() {
        return baseUrl + ALLOT_TRACKING_PATH;
    }

    public String trackOrderUrl() {
        return baseUrl + TRACK_ORDER_PATH;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
