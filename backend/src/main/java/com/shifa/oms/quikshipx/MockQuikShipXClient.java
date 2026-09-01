package com.shifa.oms.quikshipx;

import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import com.shifa.oms.quikshipx.QuikShipXModels.TrackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic, no-network {@link QuikShipXClient} for local development and
 * tests, active when {@code app.quikshipx.mode} is unset or {@code MOCK}. Mirrors
 * {@code MockCourierClient}: it derives stable ids from the order code and lets a
 * caller stage a track status. Order codes containing {@code FAIL} simulate a
 * courier failure.
 */
@Component
@ConditionalOnProperty(name = "app.quikshipx.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockQuikShipXClient implements QuikShipXClient {

    private static final Logger log = LoggerFactory.getLogger(MockQuikShipXClient.class);
    private static final String FAIL_TOKEN = "FAIL";

    private final QuikShipXProperties properties;
    /** Staged track statuses keyed by AWB (defaults to "in transit"). */
    private final ConcurrentMap<String, String> stagedStatuses = new ConcurrentHashMap<>();

    public MockQuikShipXClient(QuikShipXProperties properties) {
        this.properties = properties;
    }

    @Override
    public CreateResult createOrder(CreatePayload payload) throws QuikShipXException {
        failIfMarked(payload.orderCode());
        String shipperOrderId = numericId(payload.orderCode(), 100000);
        log.debug("Mock QuikShipX create-order for {} -> shipperOrderId {}",
                payload.orderCode(), shipperOrderId);
        return new CreateResult(shipperOrderId, "{\"mock\":true,\"shipper_order_id\":\"" + shipperOrderId + "\"}");
    }

    @Override
    public AllotResult allotTrackingId(String shipperOrderId) throws QuikShipXException {
        String awb = "QSX" + numericId(shipperOrderId, 1000000000L);
        String labelUrl = properties.baseUrl()
                + "/download_pdf_forward_order_label_1.php?src=single_order_id&value=" + shipperOrderId;
        log.debug("Mock QuikShipX allot-tracking-id for {} -> AWB {}", shipperOrderId, awb);
        return new AllotResult(awb, properties.courierId(), "Direct_Delhivery", labelUrl);
    }

    @Override
    public TrackResult trackOrder(String awb) throws QuikShipXException {
        String status = stagedStatuses.getOrDefault(awb, "in transit");
        java.util.List<QuikShipXModels.Scan> scans = java.util.List.of(
                new QuikShipXModels.Scan(titleCase(status), "Indore_Dakachya_GW (Madhya Pradesh)",
                        "Shipment " + status, "2026-09-01 12:00:00"),
                new QuikShipXModels.Scan("In Transit", "Indore_Dakachya_GW (Madhya Pradesh)",
                        "Shipment picked up", "2026-09-01 10:00:00"));
        return new TrackResult(awb, status, null, scans);
    }

    /** Title-cases a raw status like "in transit" -> "In Transit". */
    private static String titleCase(String value) {
        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * Stages a raw QuikShipX {@code order_status} for an AWB so the next
     * {@link #trackOrder} returns it — a convenience for demonstrating the
     * tracking poll locally.
     */
    public void stage(String awb, String orderStatus) {
        stagedStatuses.put(awb, orderStatus);
    }

    private void failIfMarked(String orderCode) throws QuikShipXException {
        if (orderCode != null && orderCode.toUpperCase(Locale.ROOT).contains(FAIL_TOKEN)) {
            throw new QuikShipXException("Simulated QuikShipX failure for order " + orderCode, false);
        }
    }

    /** Stable positive numeric id derived from a base string. */
    private static String numericId(String base, long modulo) {
        String source = base == null ? "MOCK" : base;
        long hash = Math.abs((long) source.hashCode());
        return String.valueOf(hash % modulo + 1);
    }
}
