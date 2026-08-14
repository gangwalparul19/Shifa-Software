package com.shifa.oms.integration.quikshipx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically polls QuikShipX for the status of active shipments and mirrors it
 * onto the order (spec {@code shopify-quikshipx-order-sync}, status mirroring).
 *
 * <p>Gated by {@code app.quikshipx.status-feed-available}: it stays a no-op until
 * that flag is turned on (after the track-order response format has been confirmed
 * from a live call), so enabling automatic tracking is a configuration change, not
 * a deploy. Terminal shipments (delivered/returned/etc.) are skipped by the
 * tracking service. A failure for one shipment never stops the pass.
 */
@Component
public class QuikShipXTrackingPoller {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXTrackingPoller.class);

    private final QuikShipXTrackingService trackingService;
    private final QuikShipXProperties properties;

    public QuikShipXTrackingPoller(QuikShipXTrackingService trackingService,
                                   QuikShipXProperties properties) {
        this.trackingService = trackingService;
        this.properties = properties;
    }

    /** Scheduled entry point; interval from {@code app.quikshipx.track-poll-interval-ms}. */
    @Scheduled(fixedDelayString = "${app.quikshipx.track-poll-interval-ms:1800000}")
    public void scheduledPoll() {
        if (!properties.isStatusFeedAvailable()) {
            return; // status polling disabled until the track-order feed is confirmed
        }
        try {
            int tracked = trackingService.trackAllActive();
            if (tracked > 0) {
                log.info("QuikShipX tracking poll updated {} active shipment(s)", tracked);
            }
        } catch (RuntimeException e) {
            log.warn("QuikShipX tracking poll failed: {}", e.getMessage());
        }
    }
}
