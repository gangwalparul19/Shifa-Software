package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Mirrors a QuikShipX status onto the matching {@link OrderShipment}
 * (spec {@code shopify-quikshipx-order-sync}, Req 6 — status mirroring).
 *
 * <p>Deliberately mirrors QuikShipX's <b>own</b> status vocabulary as a string
 * ({@code Pending}, {@code Label Printed}, {@code Ready For Pickup}, {@code In Transit},
 * {@code Delivered}, …) rather than translating it into Shifa's internal
 * {@code OrderStatus}. The internal state machine stays authoritative for Shifa-driven
 * fulfilment; for a QuikShipX-managed order the courier owns the journey, and the admin
 * wants to see exactly the status QuikShipX shows, not a lossy translation of it.
 *
 * <p>The update is <b>monotonic</b> via {@link OrderShipment#advanceStatus}: an event whose
 * timestamp is not newer than the last one is ignored, so a duplicate or out-of-order
 * delivery cannot move the status backwards.
 */
@Service
public class QuikShipXStatusUpdateService {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXStatusUpdateService.class);

    private final OrderShipmentRepository shipmentRepository;
    private final AuditService auditService;

    public QuikShipXStatusUpdateService(OrderShipmentRepository shipmentRepository,
                                        AuditService auditService) {
        this.shipmentRepository = shipmentRepository;
        this.auditService = auditService;
    }

    /** Why a status update did or did not change a shipment. */
    public enum Outcome {
        /** The shipment's status was advanced. */
        APPLIED,
        /** The event was not newer than the last one, so it was ignored. */
        SUPERSEDED,
        /** No shipment matched any of the supplied identifiers. */
        UNKNOWN_SHIPMENT,
        /** The event carried no usable status. */
        NO_STATUS
    }

    /**
     * The result of one status update.
     *
     * @param outcome what happened
     * @param orderId the Shifa order the update resolved to, when one matched
     * @param status  the status now on the shipment (or the incoming one, for context)
     */
    public record Result(Outcome outcome, Long orderId, String status) {

        public boolean applied() {
            return outcome == Outcome.APPLIED;
        }
    }

    /**
     * Resolves the shipment from any identifier QuikShipX might send and advances its status.
     *
     * @param quikshipxOrderId    QuikShipX's own order id ({@code order_id}), preferred
     * @param quikshipxShipmentId QuikShipX's shipment id ({@code id})
     * @param orderReference      our {@code customer_order_id} ({@code SHIFA-…}), the fallback
     * @param status              the QuikShipX status token
     * @param at                  when the status occurred; defaults to now when absent
     */
    @Transactional
    public Result apply(String quikshipxOrderId, String quikshipxShipmentId,
                        String orderReference, String status, LocalDateTime at) {
        return apply(quikshipxOrderId, quikshipxShipmentId, orderReference, status, null, at);
    }

    /**
     * Mirrors a QuikShipX status and, when supplied, the AWB.
     *
     * <p>The AWB is set independently of the status-monotonicity check: it appears after the
     * order is created (never at creation), so even a "superseded" status event may be the
     * one that first carries the AWB, and losing it would be worse than recording it late.
     *
     * @param awb the AWB, or null/blank when QuikShipX has not assigned one yet
     */
    @Transactional
    public Result apply(String quikshipxOrderId, String quikshipxShipmentId,
                        String orderReference, String status, String awb, LocalDateTime at) {
        return apply(quikshipxOrderId, quikshipxShipmentId, orderReference, status, awb, at, null, null);
    }

    /**
     * Mirrors a QuikShipX status/AWB and also stores the delivering carrier and the raw
     * track-order response, so the order drawer can render the full courier lifecycle
     * timeline + scans from our own portal (V54).
     *
     * <p>The carrier and raw response are refreshed even when the status/AWB did not change,
     * so the timeline stays current; that refresh alone does not create an audit entry
     * (only a genuine status advance or a first AWB does), keeping polling quiet.
     */
    @Transactional
    public Result apply(String quikshipxOrderId, String quikshipxShipmentId,
                        String orderReference, String status, String awb, LocalDateTime at,
                        String courierName, String rawTrackResponse) {
        String trimmedStatus = status == null ? "" : status.trim();
        boolean hasAwb = awb != null && !awb.isBlank();
        if (trimmedStatus.isEmpty() && !hasAwb) {
            return new Result(Outcome.NO_STATUS, null, null);
        }

        Optional<OrderShipment> found = resolve(quikshipxOrderId, quikshipxShipmentId, orderReference);
        if (found.isEmpty()) {
            log.warn("QuikShipX update (status '{}', awb '{}') could not be matched to a shipment "
                            + "(orderId={}, shipmentId={}, reference={})",
                    trimmedStatus, awb, quikshipxOrderId, quikshipxShipmentId, orderReference);
            return new Result(Outcome.UNKNOWN_SHIPMENT, null, trimmedStatus);
        }

        OrderShipment shipment = found.get();
        LocalDateTime when = at == null ? LocalDateTime.now() : at;
        // The track-order response carries no per-status timestamp, so 'when' defaults to
        // now(); without this guard an unchanged status would "advance" (now is always newer)
        // and re-audit on every poll. Skip when the token is unchanged so polling is quiet.
        boolean sameToken = !trimmedStatus.isEmpty()
                && trimmedStatus.equalsIgnoreCase(shipment.getLastStatusToken());
        boolean statusAdvanced = !trimmedStatus.isEmpty() && !sameToken
                && shipment.advanceStatus(trimmedStatus, when);
        boolean awbAssigned = hasAwb && shipment.assignAwb(awb);

        // Refresh carrier + raw timeline regardless of a status change (quietly).
        boolean detailChanged = false;
        if (courierName != null && !courierName.isBlank()
                && !courierName.trim().equals(shipment.getCourierName())) {
            shipment.setCourierName(courierName);
            detailChanged = true;
        }
        if (rawTrackResponse != null && !rawTrackResponse.isBlank()
                && !rawTrackResponse.equals(shipment.getLastTrackResponse())) {
            shipment.setLastTrackResponse(rawTrackResponse);
            detailChanged = true;
        }

        if (!statusAdvanced && !awbAssigned && !detailChanged) {
            return new Result(Outcome.SUPERSEDED, shipment.getOrderId(), shipment.getLastStatusToken());
        }
        shipmentRepository.save(shipment);
        if (!statusAdvanced && !awbAssigned) {
            // Only the timeline/carrier snapshot was refreshed — persist it, but do not audit.
            return new Result(Outcome.SUPERSEDED, shipment.getOrderId(), shipment.getLastStatusToken());
        }

        String detail = "QuikShipX update for " + shipment.getOrderReference()
                + (statusAdvanced ? " status -> " + trimmedStatus : "")
                + (awbAssigned ? " awb -> " + shipment.getAwb() : "");
        auditService.record(null, "QUIKSHIPX", AuditActions.QUIKSHIPX_STATUS_MIRRORED,
                AuditActions.ENTITY_ORDER, String.valueOf(shipment.getOrderId()), detail);
        log.info("Mirrored QuikShipX update onto order {} (reference {}): status={} awb={}",
                shipment.getOrderId(), shipment.getOrderReference(),
                shipment.getLastStatusToken(), shipment.getAwb());
        return new Result(Outcome.APPLIED, shipment.getOrderId(), shipment.getLastStatusToken());
    }

    private Optional<OrderShipment> resolve(String quikshipxOrderId, String quikshipxShipmentId,
                                            String orderReference) {
        // Prefer QuikShipX's own order id, then its shipment id, then our reference — the
        // reference always exists on our side, so it is the reliable last resort (Req 6.15).
        if (notBlank(quikshipxOrderId)) {
            Optional<OrderShipment> byOrderId =
                    shipmentRepository.findByQuikshipxOrderId(quikshipxOrderId.trim());
            if (byOrderId.isPresent()) {
                return byOrderId;
            }
        }
        if (notBlank(quikshipxShipmentId)) {
            Optional<OrderShipment> byShipmentId =
                    shipmentRepository.findByQuikshipxShipmentId(quikshipxShipmentId.trim());
            if (byShipmentId.isPresent()) {
                return byShipmentId;
            }
        }
        if (notBlank(orderReference)) {
            return shipmentRepository.findByOrderReference(orderReference.trim());
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
