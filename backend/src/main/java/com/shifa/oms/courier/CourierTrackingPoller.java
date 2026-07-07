package com.shifa.oms.courier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled fallback that reconciles missed courier webhooks by polling the
 * {@link CourierClient} for the latest status of each in-flight shipment
 * (Req 13.2). Kept intentionally minimal: it re-uses {@link CourierStatusApplier}
 * so polled updates go through the same mapping, legality, and settlement wiring
 * as webhook updates, and are therefore idempotent.
 */
@Component
public class CourierTrackingPoller {

    private static final Logger log = LoggerFactory.getLogger(CourierTrackingPoller.class);

    private final CourierRecordRepository courierRecordRepository;
    private final CourierClient courierClient;
    private final CourierStatusApplier statusApplier;

    public CourierTrackingPoller(CourierRecordRepository courierRecordRepository,
                                 CourierClient courierClient,
                                 CourierStatusApplier statusApplier) {
        this.courierRecordRepository = courierRecordRepository;
        this.courierClient = courierClient;
        this.statusApplier = statusApplier;
    }

    /** Scheduled entry point: reconcile every 5 minutes by default. */
    @Scheduled(fixedDelayString = "${app.courier.poll-interval-ms:300000}")
    public void scheduledPoll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("Courier tracking poll cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Polls every shipment that has an AWB and applies any newer status. Returns
     * the number of updates applied. Exposed for direct invocation from tests.
     *
     * @return the count of applied status changes
     */
    public int pollOnce() {
        List<CourierRecord> records = courierRecordRepository.findByAwbIsNotNull();
        int applied = 0;
        for (CourierRecord record : records) {
            String awb = record.getAwb();
            if (awb == null || awb.isBlank()) {
                continue;
            }
            var latest = courierClient.pollLatest(awb);
            if (latest.isEmpty()) {
                continue;
            }
            if (statusApplier.applyByAwb(awb, latest.get().rawStatus()).isPresent()) {
                applied++;
            }
        }
        return applied;
    }
}
