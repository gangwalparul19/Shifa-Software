package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shifa.oms.integration.quikshipx.QuikShipXProperties.ResponseKeys;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Pure request/response codec for the QuikShipX track-order API
 * ({@code POST /api/track-order-v1}).
 *
 * <p>The request shape is confirmed by QuikShipX:
 * <pre>
 * {
 *   "tracking_details": { "tracking_no": "&lt;awb or order id&gt;", "tracking_type": "awb|order_id" },
 *   "shipper_details":  { "client_code": "...", "user_id": "...", "user_secret": "..." }
 * }
 * </pre>
 *
 * <p>The <b>response</b> shape was NOT shared, so parsing is defensive: it
 * deep-searches the JSON tree for the first matching field from the configured
 * candidate-key lists ({@link ResponseKeys}), the same tolerant approach the
 * create-order response uses. Once a real response is captured the exact field
 * names can be pinned in {@code app.quikshipx.response-keys.*} with no code change.
 *
 * <p>No Spring, no I/O — unit-testable in isolation.
 */
public final class QuikShipXTrackingCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tracking by AWB number. */
    public static final String TYPE_AWB = "awb";
    /** Tracking by order id. */
    public static final String TYPE_ORDER_ID = "order_id";

    private QuikShipXTrackingCodec() {
        // Pure static helper.
    }

    /** Builds the track-order request body for the given tracking number + type. */
    public static String buildRequestBody(String clientCode, String userId, String userSecret,
                                          String trackingNo, String trackingType) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode tracking = root.putObject("tracking_details");
        tracking.put("tracking_no", trackingNo == null ? "" : trackingNo);
        tracking.put("tracking_type", normalizeType(trackingType));
        ObjectNode shipper = root.putObject("shipper_details");
        shipper.put("client_code", clientCode == null ? "" : clientCode);
        shipper.put("user_id", userId == null ? "" : userId);
        shipper.put("user_secret", userSecret == null ? "" : userSecret);
        return root.toString();
    }

    /** {@code awb} unless the caller explicitly asked to track by order id. */
    public static String normalizeType(String trackingType) {
        return TYPE_ORDER_ID.equalsIgnoreCase(trackingType) ? TYPE_ORDER_ID : TYPE_AWB;
    }

    /**
     * Parses a track-order response into a {@link QuikShipXStatusEvent}. Unknown/absent
     * fields are left null; the status token and identifiers are best-effort via the
     * candidate keys. {@code fallbackReference} is used as the order reference when the
     * response carries none, so the event can still be resolved to our shipment.
     *
     * @throws MalformedTrackingResponse when the body is not parseable JSON
     */
    public static QuikShipXStatusEvent parse(String rawJson, ResponseKeys keys,
                                             String fallbackReference) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson == null ? "" : rawJson);
        } catch (Exception e) {
            throw new MalformedTrackingResponse("Track-order response was not valid JSON");
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new MalformedTrackingResponse("Track-order response was empty");
        }

        String status = deepFind(root, keys.statusTokenKeys());
        String awb = deepFind(root, keys.awbKeys());
        String shipmentId = deepFind(root, keys.shipmentIdKeys());
        String orderId = deepFind(root, keys.orderIdKeys());
        String reference = firstNonBlank(deepFind(root, List.of("customer_order_id", "customerOrderId")),
                fallbackReference);
        LocalDateTime statusAt = parseDateTime(deepFind(root, keys.statusAtKeys()));

        return new QuikShipXStatusEvent(shipmentId, reference, orderId, awb, status, statusAt, null,
                rawJson);
    }

    /**
     * Depth-first search for the first non-blank scalar value under any of the given
     * field names (case-insensitive), walking objects and arrays. This tolerates the
     * status living at the top level or nested (e.g. {@code data.status},
     * {@code tracking[0].status}).
     */
    private static String deepFind(JsonNode node, List<String> fieldNames) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                var entry = it.next();
                for (String wanted : fieldNames) {
                    if (entry.getKey().equalsIgnoreCase(wanted)) {
                        JsonNode value = entry.getValue();
                        if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                            return value.asText().trim();
                        }
                    }
                }
            }
            // Recurse into children after checking this level's own keys.
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                String found = deepFind(it.next().getValue(), fieldNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = deepFind(child, fieldNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Best-effort date/time parse across a few common formats; null when unparseable. */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return OffsetDateTime.parse(v).toLocalDateTime();
        } catch (Exception ignored) {
            // fall through
        }
        for (DateTimeFormatter fmt : new DateTimeFormatter[] {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")}) {
            try {
                return LocalDateTime.parse(v, fmt);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b == null || b.isBlank()) ? null : b;
    }

    /** Thrown when the track-order response body cannot be parsed as JSON. */
    public static final class MalformedTrackingResponse extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MalformedTrackingResponse(String message) {
            super(message);
        }
    }
}
