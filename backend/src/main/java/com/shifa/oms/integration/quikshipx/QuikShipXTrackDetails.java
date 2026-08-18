package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure parser that turns a QuikShipX track-order response into the courier lifecycle
 * timeline and scan history shown in Shifa's order drawer, so the whole shipment journey
 * is visible from our own portal rather than the QuikShipX portal.
 *
 * <p>The confirmed track-order success body looks like:
 * <pre>
 * {"response":[{"shipment_details":{
 *     "tracking_no":"20736021008546","order_status":"tracking id assigned",
 *     "courier_name":"Delhivery_Surface",
 *     "booked_on_datetime":"2026-08-14 11:45:44",
 *     "confirmed_on_datetime":"2026-08-14 11:52:24",
 *     "tracking_id_assigned_on_datetime":"2026-08-14 11:52:51",
 *     "label_printed_on_datetime":null, ... },
 *   "shipment_scanning":{"1":{"status_code_2":"Manifested","scan_dt":"...","location":"..."}}}]}
 * </pre>
 *
 * <p>Pure and tolerant: unknown/absent fields are skipped, a stage with no timestamp is
 * omitted, and an unparseable body yields {@link #EMPTY} rather than throwing.
 */
public final class QuikShipXTrackDetails {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** QuikShipX emits "yyyy-MM-dd HH:mm:ss"; a couple of fallbacks for safety. */
    private static final DateTimeFormatter[] FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    /**
     * The lifecycle stages in order, each mapped to the datetime field QuikShipX uses.
     * Only stages whose timestamp is present are surfaced.
     */
    private static final String[][] STAGES = {
            {"Pending", "booked_on_datetime"},
            {"Confirmed", "confirmed_on_datetime"},
            {"Tracking ID Assigned", "tracking_id_assigned_on_datetime"},
            {"Label Printed", "label_printed_on_datetime"},
            {"Picked Up", "courier_picked_up_on_datetime"},
            {"Delivered", "courier_delivered_on_datetime"},
            {"Returned", "courier_returned_on_datetime"},
            {"Lost", "courier_lost_on_datetime"}
    };

    /** An empty result used when there is no track response to parse. */
    public static final QuikShipXTrackDetails EMPTY =
            new QuikShipXTrackDetails(null, null, null, List.of(), List.of());

    private final String currentStatus;
    private final String courierName;
    private final String awb;
    private final List<Stage> timeline;
    private final List<Scan> scans;

    public QuikShipXTrackDetails(String currentStatus, String courierName, String awb,
                                 List<Stage> timeline, List<Scan> scans) {
        this.currentStatus = currentStatus;
        this.courierName = courierName;
        this.awb = awb;
        this.timeline = timeline == null ? List.of() : List.copyOf(timeline);
        this.scans = scans == null ? List.of() : List.copyOf(scans);
    }

    /** One lifecycle stage that has occurred. */
    public record Stage(String label, LocalDateTime at) {
    }

    /** One courier scan event (most recent first in {@link #scans()}). */
    public record Scan(LocalDateTime at, String status, String location, String instructions) {
    }

    public String currentStatus() {
        return currentStatus;
    }

    public String courierName() {
        return courierName;
    }

    public String awb() {
        return awb;
    }

    public List<Stage> timeline() {
        return timeline;
    }

    public List<Scan> scans() {
        return scans;
    }

    /** Parses a raw track-order response; never throws. */
    public static QuikShipXTrackDetails parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (Exception e) {
            return EMPTY;
        }
        JsonNode details = firstDeep(root, "shipment_details");
        JsonNode scanning = firstDeep(root, "shipment_scanning");

        String status = details == null ? null : text(details, "order_status");
        String courier = details == null ? null : text(details, "courier_name");
        String awb = details == null ? null : text(details, "tracking_no");

        List<Stage> timeline = new ArrayList<>();
        if (details != null) {
            for (String[] stage : STAGES) {
                LocalDateTime at = dateTime(text(details, stage[1]));
                if (at != null) {
                    timeline.add(new Stage(stage[0], at));
                }
            }
        }

        List<Scan> scans = new ArrayList<>();
        if (scanning != null && scanning.isObject()) {
            for (var it = scanning.properties().iterator(); it.hasNext(); ) {
                JsonNode scan = it.next().getValue();
                LocalDateTime at = dateTime(text(scan, "scan_dt"));
                String scanStatus = firstNonBlank(text(scan, "status_code_2"), text(scan, "status_code"));
                String location = text(scan, "location");
                String instructions = text(scan, "instructions");
                if (at != null || scanStatus != null || location != null) {
                    scans.add(new Scan(at, scanStatus, location, instructions));
                }
            }
            // Most recent scan first.
            scans.sort(Comparator.comparing(Scan::at,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }

        return new QuikShipXTrackDetails(status, courier, awb, timeline, scans);
    }

    private static JsonNode firstDeep(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(field);
            if (direct != null && !direct.isNull()) {
                return direct;
            }
            for (var it = node.properties().iterator(); it.hasNext(); ) {
                JsonNode found = firstDeep(it.next().getValue(), field);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = firstDeep(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter fmt : FORMATS) {
            try {
                return LocalDateTime.parse(value.trim(), fmt);
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
}
