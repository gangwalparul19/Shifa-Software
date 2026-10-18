package com.shifa.oms.quikshipx;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists the QuikShipX courier scan timeline for an order (public
 * order-tracking feature, Option B) so the public {@code /track-order} page can
 * render the detailed timeline without a live QuikShipX call per anonymous visit.
 *
 * <p>The QuikShipX {@code track-order-v1} response is the FULL timeline, so each
 * refresh REPLACES the whole stored set for the order (delete-then-insert) — this
 * keeps the store consistent with the courier and avoids duplicate/merge logic.
 * Called from both track paths that already hold the scans: the admin
 * live-tracking view ({@code QuikShipXService.trackLive}) and the scheduled
 * courier poller ({@code QuikShipXCourierClient.pollLatest}).
 */
@Service
public class ShipmentTrackingStore {

    private final ShipmentTrackingEventRepository repository;

    public ShipmentTrackingStore(ShipmentTrackingEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Replaces the stored scan timeline for an order with {@code scans} (newest
     * first, as QuikShipX returns them). A no-op when {@code scans} is null or
     * empty — a track cycle that returns no scans must not wipe a previously
     * captured timeline (the courier sometimes returns an empty scanning block
     * transiently before re-populating it).
     *
     * @param orderId the order whose timeline to refresh
     * @param scans   the latest full scan timeline (newest first); null/empty = no-op
     */
    @Transactional
    public void replace(Long orderId, List<QuikShipXModels.Scan> scans) {
        if (orderId == null || scans == null || scans.isEmpty()) {
            return;
        }
        repository.deleteByOrderId(orderId);
        int i = 0;
        for (QuikShipXModels.Scan scan : scans) {
            repository.save(new ShipmentTrackingEvent(
                    orderId, scan.status(), scan.location(), scan.instructions(), scan.scanAt(), i++));
        }
    }
}
