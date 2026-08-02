package com.shifa.oms.integration.quikshipx;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the QuikShipX integration, bound from {@code app.quikshipx.*}
 * (contract: {@code docs/QUIKSHIPX-API-V1.md}).
 *
 * <p>Three things about this integration are unusual and shape the properties:
 *
 * <ul>
 *   <li><b>Credentials go in the request body</b>, not a header. QuikShipX takes
 *       {@code client_code}, {@code user_id} and {@code user_secret} inside
 *       {@code shipper_details}, so there is no API-key header to configure.</li>
 *   <li><b>Test versus live is chosen by secret</b>, not by URL. A TEST secret files
 *       the order under QuikShipX's Test section, a LIVE secret under Pending.</li>
 *   <li><b>The response body is undocumented.</b> {@link ResponseKeys} lists candidate
 *       field names per identifier so the first live calls cannot fail on a guess;
 *       the real names get pinned from stored raw responses afterwards.</li>
 * </ul>
 *
 * @param enabled               master switch; OFF by default, so the internal label +
 *                              packing flow behaves exactly as it did before this feature
 * @param mode                  client backend: {@code MOCK} (default, no network) or {@code HTTP}
 * @param baseUrl               API host; the one operation is {@code POST {baseUrl}/api/create-order-v1}
 * @param clientCode            {@code shipper_details.client_code}
 * @param userId                {@code shipper_details.user_id}
 * @param userSecret            {@code shipper_details.user_secret} (TEST or LIVE)
 * @param secretMode            {@code TEST} or {@code LIVE}; stamps {@code order_shipments.is_test}
 * @param orderReferencePrefix  prefix making the channel visible in {@code customer_order_id},
 *                              since the API has no channel field
 * @param statusFeedAvailable   whether QuikShipX exposes a status webhook or query. The
 *                              confirmed contract exposes NEITHER, so this is false and the
 *                              status webhook + poller beans do not exist
 * @param webhookSecret         HMAC secret for the status webhook, once one exists
 * @param requestTimeout        per-call timeout, after which a submission counts as retryable
 * @param maxAttempts           total publication attempts including the first (1 + 5 retries)
 * @param retryBackoff          first-retry delay; the ladder doubles from here
 * @param retryMaxBackoff       ceiling so a long-failing order still retries usefully
 * @param responseKeys          candidate response field names per identifier
 */
@ConfigurationProperties(prefix = "app.quikshipx")
public record QuikShipXProperties(
        Boolean enabled,
        String mode,
        String baseUrl,
        String clientCode,
        String userId,
        String userSecret,
        String secretMode,
        String orderReferencePrefix,
        Boolean statusFeedAvailable,
        String webhookSecret,
        Duration requestTimeout,
        Integer maxAttempts,
        Duration retryBackoff,
        Duration retryMaxBackoff,
        ResponseKeys responseKeys) {

    /** The confirmed create-order path, appended to {@link #baseUrl()}. */
    public static final String CREATE_ORDER_PATH = "/api/create-order-v1";

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
        if (secretMode == null || secretMode.isBlank()) {
            // Default to TEST so a misconfiguration books into QuikShipX's Test
            // section rather than dispatching a real parcel.
            secretMode = "TEST";
        }
        if (orderReferencePrefix == null) {
            orderReferencePrefix = "SHIFA-";
        }
        if (statusFeedAvailable == null) {
            statusFeedAvailable = Boolean.FALSE;
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(15);
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 6;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
        if (retryMaxBackoff == null) {
            retryMaxBackoff = Duration.ofMinutes(15);
        }
        if (responseKeys == null) {
            responseKeys = new ResponseKeys(null, null, null, null, null, null);
        }
    }

    /**
     * Candidate response field names, most likely first, as comma-separated lists.
     *
     * <p>Needed because the supplied specification documents the request in full but
     * shows no response, so the identifier field names are unknown. Rather than guess
     * one name and fail on the first live call, the parser tries each candidate.
     */
    public record ResponseKeys(
            String shipmentId,
            String orderId,
            String awb,
            String courierName,
            String trackingUrl,
            String labelUrl) {

        public ResponseKeys {
            if (shipmentId == null || shipmentId.isBlank()) {
                // 'id' is QuikShipX's shipment/tracking id (e.g. 65580852235). Their own
                // order id lives under 'order_id' and is captured separately below, so it
                // is deliberately NOT listed here.
                shipmentId = "shipment_id,shipmentId,id";
            }
            if (orderId == null || orderId.isBlank()) {
                // QuikShipX's own order id, e.g. {"order_id":177286}. The number to quote
                // when tracking the order in their portal.
                orderId = "order_id,orderId,order_number,orderNumber";
            }
            if (awb == null || awb.isBlank()) {
                awb = "awb,awb_number,awbNumber,waybill,waybill_number,tracking_number,trackingNumber";
            }
            if (courierName == null || courierName.isBlank()) {
                courierName = "courier,courier_name,courierName,carrier,carrier_name";
            }
            if (trackingUrl == null || trackingUrl.isBlank()) {
                trackingUrl = "tracking_url,trackingUrl,track_url,trackUrl";
            }
            if (labelUrl == null || labelUrl.isBlank()) {
                labelUrl = "label_url,labelUrl,label,shipping_label_url,shippingLabelUrl";
            }
        }

        public List<String> shipmentIdKeys() {
            return split(shipmentId);
        }

        public List<String> orderIdKeys() {
            return split(orderId);
        }

        public List<String> awbKeys() {
            return split(awb);
        }

        public List<String> courierNameKeys() {
            return split(courierName);
        }

        public List<String> trackingUrlKeys() {
            return split(trackingUrl);
        }

        public List<String> labelUrlKeys() {
            return split(labelUrl);
        }

        private static List<String> split(String csv) {
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isMock() {
        return !"HTTP".equalsIgnoreCase(mode);
    }

    /** Whether submissions are booked against QuikShipX's Test section. */
    public boolean isTestSecret() {
        return !"LIVE".equalsIgnoreCase(secretMode);
    }

    public boolean isStatusFeedAvailable() {
        return Boolean.TRUE.equals(statusFeedAvailable);
    }

    /** Whether a status-webhook signing secret is configured (Req 6.1). */
    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /** The full create-order endpoint. */
    public String createOrderUrl() {
        String host = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return host + CREATE_ORDER_PATH;
    }

    /**
     * Whether the body-carried credentials are present. Without them a submission
     * would be rejected, so publication is skipped and the admin is told which
     * setting is missing rather than burning the retry ladder (Req 15.12).
     */
    public boolean hasCredentials() {
        return notBlank(clientCode) && notBlank(userId) && notBlank(userSecret);
    }

    /** Names the missing credential settings, for an admin-facing failure reason. */
    public String missingCredentials() {
        StringBuilder missing = new StringBuilder();
        appendIfBlank(missing, clientCode, "app.quikshipx.client-code");
        appendIfBlank(missing, userId, "app.quikshipx.user-id");
        appendIfBlank(missing, userSecret, "app.quikshipx.user-secret");
        return missing.toString();
    }

    private static void appendIfBlank(StringBuilder target, String value, String name) {
        if (!notBlank(value)) {
            if (!target.isEmpty()) {
                target.append(", ");
            }
            target.append(name);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
