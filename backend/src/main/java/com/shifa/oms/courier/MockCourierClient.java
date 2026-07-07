package com.shifa.oms.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic, in-memory {@link CourierClient} for local development and tests
 * (design "[Free-tier]": there is no live Courier API locally).
 *
 * <p><strong>AWB assignment.</strong> {@link #assign} derives a stable AWB from
 * the order code (so the same order always gets the same AWB), names the courier
 * {@code Shifa Express}, and sets an estimated delivery five days out. To let a
 * demo/test exercise the failure path (Req 12.4), assignment throws a
 * {@link CourierClientException} when the order code contains {@code FAIL}
 * (case-insensitive) — no external state or randomness needed.
 *
 * <p><strong>Simulating tracking updates.</strong> Because there is no real
 * courier to push webhooks, {@link #simulate(String, String)} records a raw
 * status for an AWB that {@link #pollLatest} then returns, so the scheduled poll
 * fallback can be demonstrated end to end. The primary demo path, however, is to
 * POST a signed payload to {@code /api/webhooks/courier}; this client's simulate
 * map is a convenience for the poll fallback.
 */
@Component
@ConditionalOnProperty(prefix = "app.courier", name = "mode", havingValue = "MOCK", matchIfMissing = true)
public class MockCourierClient implements CourierClient {

    private static final Logger log = LoggerFactory.getLogger(MockCourierClient.class);

    /** Order codes containing this token trigger a simulated courier failure. */
    private static final String FAIL_TOKEN = "FAIL";

    private final String courierName;
    private final ConcurrentMap<String, String> simulatedStatuses = new ConcurrentHashMap<>();

    public MockCourierClient(CourierProperties properties) {
        this.courierName = properties.companyName();
    }

    @Override
    public CourierAssignmentResult assign(CourierAssignmentRequest request) {
        String orderCode = request.orderCode();
        if (orderCode != null && orderCode.toUpperCase(Locale.ROOT).contains(FAIL_TOKEN)) {
            throw new CourierClientException(
                    "Simulated courier failure for order " + orderCode + " (FAIL token).");
        }
        String awb = awbFor(orderCode);
        log.debug("Mock courier assigned AWB {} to order {}", awb, orderCode);
        return new CourierAssignmentResult(awb, courierName, LocalDate.now().plusDays(5));
    }

    @Override
    public Optional<CourierTrackingEvent> pollLatest(String awb) {
        if (awb == null) {
            return Optional.empty();
        }
        String raw = simulatedStatuses.get(awb);
        return raw == null ? Optional.empty() : Optional.of(new CourierTrackingEvent(awb, raw));
    }

    /**
     * Records a raw courier status for an AWB so {@link #pollLatest} returns it —
     * a convenience for demonstrating the scheduled poll fallback locally.
     *
     * @param awb       the AWB to update
     * @param rawStatus the raw courier status token (e.g. {@code delivered})
     */
    public void simulate(String awb, String rawStatus) {
        simulatedStatuses.put(awb, rawStatus);
    }

    /** Deterministic AWB derived from the order code (stable across calls). */
    private String awbFor(String orderCode) {
        String base = orderCode == null ? "ORDER" : orderCode;
        int hash = Math.abs(base.hashCode());
        return String.format("SFX%010d", hash);
    }
}
