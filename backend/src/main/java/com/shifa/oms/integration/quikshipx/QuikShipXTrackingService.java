package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.common.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Queries QuikShipX for a shipment's current status and mirrors it onto the
 * {@link OrderShipment} via {@link QuikShipXStatusUpdateService}
 * (spec {@code shopify-quikshipx-order-sync}, status mirroring — enabled now that
 * QuikShipX shared the track-order API).
 *
 * <p>Picks the tracking key per shipment: AWB (tracking_type {@code awb}) when
 * present, else QuikShipX's order id, else our order reference (both
 * {@code order_id}). Used by both the admin "Track now" action and the scheduled
 * poller.
 */
@Service
public class QuikShipXTrackingService {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXTrackingService.class);

    /**
     * Terminal QuikShipX statuses the poller stops tracking (case-insensitive
     * substring match), so a delivered/returned parcel isn't polled forever.
     */
    private static final Set<String> TERMINAL = Set.of(
            "delivered", "rto delivered", "returned", "cancelled", "canceled", "lost");

    private final QuikShipXClient client;
    private final QuikShipXStatusUpdateService statusUpdateService;
    private final OrderShipmentRepository shipmentRepository;

    public QuikShipXTrackingService(QuikShipXClient client,
                                    QuikShipXStatusUpdateService statusUpdateService,
                                    OrderShipmentRepository shipmentRepository) {
        this.client = client;
        this.statusUpdateService = statusUpdateService;
        this.shipmentRepository = shipmentRepository;
    }

    /** The outcome of tracking one shipment. */
    public record TrackResult(boolean ok, String status, String awb, String detail,
                              String rawResponse) {
    }

    /**
     * Tracks the shipment for an order and mirrors the status. Throws
     * {@link ResourceNotFoundException} when the order has no shipment.
     */
    public TrackResult trackByOrderId(Long orderId) {
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no QuikShipX shipment to track."));
        return trackOne(shipment);
    }

    /**
     * Records an AWB an admin read off the QuikShipX portal, then tracks by it.
     *
     * <p>This is the reliable path when QuikShipX's undocumented create-order response
     * carried no AWB or order id: without an AWB we can only fall back to our own order
     * reference, which QuikShipX tracks as {@code order_id} and does not recognise
     * ("Shipment Not Found"). The AWB (tracking_type {@code awb}) is the key QuikShipX
     * confirmed works. Transactional so the corrected AWB is persisted before tracking.
     *
     * @param orderId the Shifa order
     * @param awb     the AWB from the portal; blank leaves any existing AWB untouched
     */
    @Transactional
    public TrackResult setAwbAndTrack(Long orderId, String awb) {
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no QuikShipX shipment to track."));
        if (shipment.overwriteAwb(awb)) {
            shipmentRepository.save(shipment);
        }
        return trackOne(shipment);
    }

    /**
     * Tracks a single shipment against QuikShipX and mirrors the returned status.
     * Never throws for a QuikShipX-side failure — it is returned as {@code ok=false}
     * with a detail so the caller (endpoint or poller) can surface/log it.
     */
    public TrackResult trackOne(OrderShipment shipment) {
        String awb = shipment.getAwb();
        String trackingNo;
        String type;
        if (notBlank(awb)) {
            // Once assigned, the AWB is the most direct key (tracking_type=awb).
            trackingNo = awb.trim();
            type = QuikShipXTrackingCodec.TYPE_AWB;
        } else if (notBlank(shipment.getOrderReference())) {
            // Track by OUR customer_order_id (the reference we sent), which QuikShipX
            // resolves as tracking_type=order_id and returns the AWB + status for.
            // NOTE: QuikShipX's own numeric order_id (e.g. 191181) is NOT a valid tracking
            // key — tracking by it returns "Shipment Not Found" — so it is deliberately
            // never used here; it is kept only for portal cross-reference.
            trackingNo = shipment.getOrderReference().trim();
            type = QuikShipXTrackingCodec.TYPE_ORDER_ID;
        } else {
            return new TrackResult(false, shipment.getLastStatusToken(), awb,
                    "No AWB or order reference to track with yet.", null);
        }

        QuikShipXStatusEvent event;
        try {
            event = client.fetchStatus(trackingNo, type);
        } catch (QuikShipXClientException e) {
            log.warn("QuikShipX track failed for order {} ({} {}): {}",
                    shipment.getOrderId(), type, trackingNo, e.getMessage());
            return new TrackResult(false, shipment.getLastStatusToken(), awb, e.getMessage(), null);
        }

        // Parse the carrier from the raw response and store the whole response, so the
        // order drawer can show the full lifecycle timeline + scans from our own portal.
        QuikShipXTrackDetails details = QuikShipXTrackDetails.parse(event.rawPayload());
        QuikShipXStatusUpdateService.Result result = statusUpdateService.apply(
                shipment.getQuikshipxOrderId(),
                shipment.getQuikshipxShipmentId(),
                shipment.getOrderReference(),
                event.statusToken(),
                event.awb(),
                event.statusAt(),
                details.courierName(),
                event.rawPayload());

        return new TrackResult(true, result.status(), event.awb(),
                "QuikShipX status: " + result.outcome(), event.rawPayload());
    }

    /**
     * Tracks all non-terminal trackable shipments (used by the poller).
     *
     * @return the number of shipments tracked this pass
     */
    public int trackAllActive() {
        List<OrderShipment> shipments = shipmentRepository.findTrackable();
        int tracked = 0;
        for (OrderShipment shipment : shipments) {
            if (isTerminal(shipment.getLastStatusToken())) {
                continue;
            }
            trackOne(shipment);
            tracked++;
        }
        return tracked;
    }

    private static boolean isTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.toLowerCase();
        return TERMINAL.stream().anyMatch(s::contains);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
