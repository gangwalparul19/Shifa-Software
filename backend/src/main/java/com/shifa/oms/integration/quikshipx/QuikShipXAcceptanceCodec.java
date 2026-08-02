package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a QuikShipX create-order response into a {@link ShipmentAcceptance}.
 *
 * <p><b>Deliberately tolerant.</b> The contract documents no response body, so this
 * parser cannot assume a shape. It:
 *
 * <ul>
 *   <li>searches for each identifier under a <i>configured list of candidate key
 *       names</i>, first match wins, so a wrong guess is a config change not a redeploy;</li>
 *   <li>searches <b>recursively</b>, because the identifiers may sit under a wrapper
 *       such as {@code {"data": {...}}} or {@code {"result": {"shipment": {...}}}};</li>
 *   <li>treats a 2xx with no recognised key as an <b>acceptance</b>, not a failure —
 *       QuikShipX has the shipment either way, and failing here would strand the order
 *       at {@code APPROVED} and trigger pointless retries;</li>
 *   <li>never throws on unparseable JSON, returning a reference-only acceptance instead,
 *       for the same reason.</li>
 * </ul>
 *
 * <p>The raw response is carried through so the real key names can be read off
 * production traffic in the admin health console.
 *
 * <p>Pure: no Spring, no I/O, no clock.
 */
public final class QuikShipXAcceptanceCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Guards against a pathological or hostile deeply-nested response. */
    private static final int MAX_DEPTH = 6;

    /** Field names whose value signals an application-level failure. */
    private static final java.util.Set<String> FAILURE_STATUS_VALUES =
            java.util.Set.of("failure", "failed", "error", "false", "0");

    /**
     * Whether a set of failure messages means "the order id already exists on QuikShipX".
     *
     * <p>QuikShipX enforces uniqueness on {@code customer_order_id} and reports a duplicate
     * as {@code "Client Order ID Already Exists"}. That is not a real failure — the order is
     * already booked — so the publisher treats it as idempotent rather than an error. The
     * match is substring and case-insensitive because the exact wording is not contractual.
     */
    public static boolean isDuplicateReference(List<String> errors) {
        if (errors == null) {
            return false;
        }
        for (String error : errors) {
            if (error == null) {
                continue;
            }
            String lower = error.toLowerCase();
            if (lower.contains("already exist")
                    && (lower.contains("order id") || lower.contains("order_id")
                            || lower.contains("client order") || lower.contains("customer_order"))) {
                return true;
            }
        }
        return false;
    }

    private QuikShipXAcceptanceCodec() {
        // Pure static helper.
    }

    /**
     * Detects an application-level rejection carried inside an HTTP 2xx body.
     *
     * <p>This exists because QuikShipX returns {@code 200 OK} with a body like
     * {@code {"response":[{"status":"failure","errors":[...]}]}} when it rejects an order
     * (for example an invalid HSN code). Treating any 2xx as an acceptance therefore records
     * a phantom shipment for an order the courier never created — exactly the bug this guards
     * against. The transport-level {@code 4xx/5xx} path in {@link HttpQuikShipXClient} still
     * handles ordinary HTTP errors; this covers the "soft failure" QuikShipX layers on top.
     *
     * @return the human-readable error messages when the body signals failure, newest wins;
     *         an <b>empty</b> list when no failure is detected, in which case the caller keeps
     *         the tolerant "2xx means accepted" behaviour
     */
    public static List<String> detectFailure(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawResponse);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A non-JSON 2xx body carries no failure signal we can read; stay tolerant.
            return List.of();
        }
        if (root == null || root.isNull()) {
            return List.of();
        }

        // The failure signal is deliberately narrow: an explicit status of failure, or a
        // non-empty errors array. A stray scalar "message" field is NOT treated as failure,
        // because success bodies often carry one ("Order created") and mistaking that for a
        // rejection would strand every order.
        java.util.List<String> messages = new java.util.ArrayList<>();
        collectErrorArrays(root, 0, messages);
        boolean failed = hasFailureStatus(root, 0) || !messages.isEmpty();

        if (!failed) {
            return List.of();
        }
        if (messages.isEmpty()) {
            // status=failure but no error array — still a rejection, name it generically.
            messages.add("QuikShipX reported status=failure");
        }
        return List.copyOf(messages);
    }

    /** Whether any {@code status}/{@code success} field carries a failure value. */
    private static boolean hasFailureStatus(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return false;
        }
        if (node.isObject()) {
            for (String field : new String[]{"status", "success", "result"}) {
                JsonNode value = node.get(field);
                if (value != null && value.isValueNode()
                        && FAILURE_STATUS_VALUES.contains(value.asText().trim().toLowerCase())) {
                    return true;
                }
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (hasFailureStatus(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasFailureStatus(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gathers failure messages: from any {@code errors}/{@code messages} <b>array</b>, or a
     * scalar {@code error}. A scalar {@code message} is intentionally ignored as a failure
     * signal (success bodies use it too); it is only surfaced once failure is established
     * elsewhere.
     */
    private static void collectErrorArrays(JsonNode node, int depth, List<String> into) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node.isObject()) {
            for (String field : new String[]{"errors", "messages"}) {
                JsonNode value = node.get(field);
                if (value != null && value.isArray()) {
                    for (JsonNode item : value) {
                        if (item.isValueNode() && !item.asText().isBlank()) {
                            into.add(item.asText().trim());
                        }
                    }
                }
            }
            JsonNode error = node.get("error");
            if (error != null && error.isValueNode() && !error.asText().isBlank()) {
                into.add(error.asText().trim());
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                collectErrorArrays(entry.getValue(), depth + 1, into);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectErrorArrays(item, depth + 1, into);
            }
        }
    }

    /**
     * @param orderReference the value we sent as {@code customer_order_id}
     * @param test           whether the TEST secret was used
     * @param rawResponse    the response body exactly as received; may be null or blank
     * @param keys           configured candidate field names per identifier
     */
    public static ShipmentAcceptance parse(String orderReference, boolean test, String rawResponse,
                                           QuikShipXProperties.ResponseKeys keys) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return ShipmentAcceptance.referenceOnly(orderReference, test, rawResponse);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(rawResponse);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A non-JSON 2xx body still means QuikShipX accepted the shipment.
            return ShipmentAcceptance.referenceOnly(orderReference, test, rawResponse);
        }
        if (root == null || root.isNull()) {
            return ShipmentAcceptance.referenceOnly(orderReference, test, rawResponse);
        }

        return new ShipmentAcceptance(
                orderReference,
                findFirst(root, keys.shipmentIdKeys()).orElse(null),
                findFirst(root, keys.orderIdKeys()).orElse(null),
                findFirst(root, keys.awbKeys()).orElse(null),
                findFirst(root, keys.courierNameKeys()).orElse(null),
                findFirst(root, keys.trackingUrlKeys()).orElse(null),
                findFirst(root, keys.labelUrlKeys()).orElse(null),
                test,
                rawResponse);
    }

    /**
     * The first non-blank scalar found for any candidate key, searched in candidate
     * order so the configured preference wins over document position.
     */
    static Optional<String> findFirst(JsonNode root, List<String> candidateKeys) {
        for (String key : candidateKeys) {
            Optional<String> found = search(root, key, 0);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Depth-limited recursive search for a scalar value under the given key. */
    private static Optional<String> search(JsonNode node, String key, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (node.isObject()) {
            JsonNode direct = node.get(key);
            if (direct != null && direct.isValueNode() && !direct.isNull()) {
                String text = direct.asText();
                if (text != null && !text.isBlank()) {
                    return Optional.of(text.trim());
                }
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                Optional<String> nested = search(entry.getValue(), key, depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> nested = search(item, key, depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }
}
