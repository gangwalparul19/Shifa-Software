package com.shifa.oms.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import com.shifa.oms.quikshipx.QuikShipXModels.TrackResult;

import java.util.List;

/**
 * Pure parsing of QuikShipX JSON responses.
 *
 * <p>The allot-tracking-id and track-order responses are documented
 * ({@code response[0].tracking_details} / {@code response[0].shipment_details}),
 * so those are read precisely. The create-order response is <b>undocumented</b>,
 * so {@link #parseCreate} does a tolerant deep search for QuikShipX's order id
 * under any of several candidate keys and never fails on a missing id (the
 * shipment is still Pending; the id can be back-filled once its real key is
 * pinned from a live response).
 *
 * <p>All three operations can return a 200 body reporting a soft failure —
 * {@code response[0]: {"errors":[...],"status":"failure"}} (e.g. "Shipment Not
 * Found" between booking and tracking-id allotment); {@link #detectFailure}
 * surfaces those so a caller does not mistake them for success.
 */
public final class QuikShipXResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Candidate keys for QuikShipX's own order id in the create-order response. */
    private static final List<String> ORDER_ID_KEYS =
            List.of("shipper_order_id", "shipperOrderId", "order_id", "orderId", "id");

    private QuikShipXResponseParser() {
    }

    /** Thrown when a documented response cannot be read at all. */
    public static final class MalformedResponse extends RuntimeException {
        public MalformedResponse(String message) {
            super(message);
        }
    }

    /**
     * Detects a soft-failure body and returns its error messages, or an empty
     * list when the body looks successful. Looks at the top level and inside
     * {@code response[0]}.
     */
    public static List<String> detectFailure(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = readTree(body);
        if (root == null) {
            return List.of();
        }
        JsonNode node = firstResponseElement(root, root);
        JsonNode status = node.get("status");
        boolean failure = status != null && "failure".equalsIgnoreCase(status.asText(""));
        JsonNode errors = node.get("errors");
        if (!failure && (errors == null || !errors.isArray() || errors.isEmpty())) {
            return List.of();
        }
        java.util.List<String> messages = new java.util.ArrayList<>();
        if (errors != null && errors.isArray()) {
            errors.forEach(e -> messages.add(e.asText()));
        }
        if (messages.isEmpty() && failure) {
            messages.add("QuikShipX reported failure");
        }
        return messages;
    }

    /** Parses a create-order response, best-effort extracting QuikShipX's order id. */
    public static CreateResult parseCreate(String body) {
        JsonNode root = readTree(body);
        String shipperOrderId = root == null ? null : findFirst(root, ORDER_ID_KEYS);
        return new CreateResult(shipperOrderId, body);
    }

    /** Parses an allot-tracking-id response ({@code response[0].tracking_details}). */
    public static AllotResult parseAllot(String body) {
        JsonNode root = requireTree(body);
        JsonNode details = firstResponseElement(root, null).path("tracking_details");
        if (details.isMissingNode() || !details.isObject()) {
            throw new MalformedResponse("allot-tracking-id response had no tracking_details");
        }
        String awb = text(details, "tracking_id", "awb", "tracking_no");
        return new AllotResult(
                awb,
                text(details, "courier_id"),
                text(details, "sub_courier_name", "courier_name"),
                text(details, "pdf_label_url", "label_url"));
    }

    /** Parses a track-order response ({@code response[0].shipment_details} + scanning). */
    public static TrackResult parseTrack(String body) {
        JsonNode root = requireTree(body);
        JsonNode element = firstResponseElement(root, null);
        JsonNode details = element.path("shipment_details");
        if (details.isMissingNode() || !details.isObject()) {
            throw new MalformedResponse("track-order response had no shipment_details");
        }
        return new TrackResult(
                text(details, "tracking_no", "awb"),
                text(details, "order_status", "status"),
                text(details, "order_status_id"),
                parseScans(element.path("shipment_scanning")));
    }

    /**
     * Parses the {@code shipment_scanning} object (numeric string keys "1".."n",
     * each a scan event) into a timeline, newest first. QuikShipX returns them in
     * descending time order; we sort by {@code scan_dt} desc defensively.
     */
    private static List<QuikShipXModels.Scan> parseScans(JsonNode scanning) {
        if (scanning == null || !scanning.isObject()) {
            return List.of();
        }
        java.util.List<QuikShipXModels.Scan> scans = new java.util.ArrayList<>();
        for (JsonNode node : scanning) {
            if (node == null || !node.isObject()) {
                continue;
            }
            scans.add(new QuikShipXModels.Scan(
                    text(node, "status_code_2", "status", "status_code"),
                    text(node, "location"),
                    text(node, "instructions"),
                    text(node, "scan_dt", "scan_date", "date")));
        }
        scans.sort((a, b) -> {
            String x = a.scanAt() == null ? "" : a.scanAt();
            String y = b.scanAt() == null ? "" : b.scanAt();
            return y.compareTo(x); // newest first (ISO-ish timestamps sort lexically)
        });
        return scans;
    }

    // --- helpers ------------------------------------------------------------

    /** Returns {@code response[0]} when present, else {@code fallback} (or the root). */
    private static JsonNode firstResponseElement(JsonNode root, JsonNode fallback) {
        JsonNode response = root.get("response");
        if (response != null && response.isArray() && !response.isEmpty()) {
            return response.get(0);
        }
        if (response != null && response.isObject()) {
            return response;
        }
        return fallback != null ? fallback : root;
    }

    /** First non-blank text among the given keys on a node, else null. */
    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String s = value.asText("");
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Recursively finds the first non-blank value for any of the candidate keys. */
    private static String findFirst(JsonNode node, List<String> keys) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && (value.isTextual() || value.isNumber())) {
                    String s = value.asText("");
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            }
            for (JsonNode child : node) {
                String found = findFirst(child, keys);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirst(child, keys);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static JsonNode requireTree(String body) {
        JsonNode root = readTree(body);
        if (root == null) {
            throw new MalformedResponse("QuikShipX response was not valid JSON");
        }
        return root;
    }
}
