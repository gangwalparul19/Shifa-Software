package com.shifa.oms.courier;

import com.shifa.oms.statemachine.OrderStatus;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a courier's raw status token to an internal {@link OrderStatus}
 * (Req 13.1, 13.2, 17.1; Property 7).
 *
 * <p>The mapping is total over the recognised courier vocabulary and pure — it
 * decides only which internal status a courier token <em>means</em>, not whether
 * that transition is legal from the order's current status. Legality is enforced
 * separately by the {@link com.shifa.oms.statemachine.OrderStatusStateMachine}
 * when the mapped status is applied. Unknown tokens map to empty and are ignored.
 *
 * <p>Recognised tokens (case- and separator-insensitive; {@code -}/space are
 * treated as {@code _}):
 * <ul>
 *   <li>{@code pickup}, {@code picked_up}, {@code dispatched} &rarr; {@code DISPATCHED} (Req 13.1)</li>
 *   <li>{@code in_transit} &rarr; {@code IN_TRANSIT}</li>
 *   <li>{@code out_for_delivery} &rarr; {@code OUT_FOR_DELIVERY}</li>
 *   <li>{@code delivered} &rarr; {@code DELIVERED}</li>
 *   <li>{@code return}, {@code returned}, {@code rto} &rarr; {@code RTO}</li>
 *   <li>{@code lost}, {@code damaged}, {@code missing} &rarr; {@code REDISPATCH} (Req 17.1)</li>
 *   <li>{@code customer_rejected}, {@code refused}, {@code rejected} &rarr; {@code CUSTOMER_REJECTED} (Req 11.1)</li>
 *   <li>{@code delivery_failed}, {@code failed}, {@code undelivered}, {@code attempt_failed} &rarr; {@code DELIVERY_FAILED} (Req 11.2)</li>
 * </ul>
 */
public final class CourierStatusMapper {

    private static final Map<String, OrderStatus> MAPPING = Map.ofEntries(
            Map.entry("pickup", OrderStatus.DISPATCHED),
            Map.entry("picked_up", OrderStatus.DISPATCHED),
            Map.entry("picked", OrderStatus.DISPATCHED),
            Map.entry("pickup_done", OrderStatus.DISPATCHED),
            Map.entry("pickup_complete", OrderStatus.DISPATCHED),
            Map.entry("pickup_completed", OrderStatus.DISPATCHED),
            // QuikShipX "Ready For Pickup" precedes the courier scan; treat it as
            // dispatched so a QuikShipX-tracked order advances past Courier_Assigned.
            Map.entry("ready_for_pickup", OrderStatus.DISPATCHED),
            Map.entry("dispatched", OrderStatus.DISPATCHED),
            // NOTE: pre-movement labels — "Manifested" / "Manifest" / "Shipment
            // Created" / "Shipment Booked" / "Pickup Scheduled" / "Pickup Assigned"
            // — are deliberately NOT mapped. The AWB/label exists but the courier
            // has not actually picked the parcel up yet, so our portal must stay at
            // "Tracking ID Assigned" (COURIER_ASSIGNED). The order only advances
            // once a real pickup / in-transit scan arrives (tokens below). An
            // unmapped token is ignored, which keeps the current status.
            // In-transit vocabulary across Delhivery/QuikShipX scans.
            Map.entry("in_transit", OrderStatus.IN_TRANSIT),
            Map.entry("intransit", OrderStatus.IN_TRANSIT),
            Map.entry("transit", OrderStatus.IN_TRANSIT),
            Map.entry("shipped", OrderStatus.IN_TRANSIT),
            Map.entry("in_scan", OrderStatus.IN_TRANSIT),
            Map.entry("bag_added", OrderStatus.IN_TRANSIT),
            Map.entry("bagged", OrderStatus.IN_TRANSIT),
            Map.entry("received_at_facility", OrderStatus.IN_TRANSIT),
            Map.entry("reached_at_hub", OrderStatus.IN_TRANSIT),
            Map.entry("reached_hub", OrderStatus.IN_TRANSIT),
            Map.entry("reached_destination", OrderStatus.IN_TRANSIT),
            Map.entry("reached", OrderStatus.IN_TRANSIT),
            Map.entry("arrived_at_hub", OrderStatus.IN_TRANSIT),
            Map.entry("facility_received", OrderStatus.IN_TRANSIT),
            // Out-for-delivery vocabulary.
            Map.entry("out_for_delivery", OrderStatus.OUT_FOR_DELIVERY),
            Map.entry("ofd", OrderStatus.OUT_FOR_DELIVERY),
            Map.entry("out_for_delivered", OrderStatus.OUT_FOR_DELIVERY),
            Map.entry("outfordelivery", OrderStatus.OUT_FOR_DELIVERY),
            // Delivered vocabulary.
            Map.entry("delivered", OrderStatus.DELIVERED),
            Map.entry("delivered_to_consignee", OrderStatus.DELIVERED),
            Map.entry("delivery_successful", OrderStatus.DELIVERED),
            Map.entry("delivery_success", OrderStatus.DELIVERED),
            Map.entry("return", OrderStatus.RTO),
            Map.entry("returned", OrderStatus.RTO),
            Map.entry("rto", OrderStatus.RTO),
            Map.entry("rto_delivered", OrderStatus.RTO),
            Map.entry("rto_in_transit", OrderStatus.RTO),
            Map.entry("lost", OrderStatus.REDISPATCH),
            Map.entry("damaged", OrderStatus.REDISPATCH),
            Map.entry("missing", OrderStatus.REDISPATCH),
            // Customer refused at the door (Req 11.1).
            Map.entry("customer_rejected", OrderStatus.CUSTOMER_REJECTED),
            Map.entry("refused", OrderStatus.CUSTOMER_REJECTED),
            Map.entry("rejected", OrderStatus.CUSTOMER_REJECTED),
            // Failed delivery attempt (Req 11.2).
            Map.entry("delivery_failed", OrderStatus.DELIVERY_FAILED),
            Map.entry("failed", OrderStatus.DELIVERY_FAILED),
            Map.entry("undelivered", OrderStatus.DELIVERY_FAILED),
            Map.entry("attempt_failed", OrderStatus.DELIVERY_FAILED),
            Map.entry("not_delivered", OrderStatus.DELIVERY_FAILED));

    private CourierStatusMapper() {
    }

    /**
     * Maps a raw courier status token to its internal {@link OrderStatus}.
     *
     * @param rawStatus the courier's status token (may be {@code null}/blank)
     * @return the internal status, or empty when the token is unrecognised
     */
    public static Optional<OrderStatus> toInternal(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return Optional.empty();
        }
        String normalized = rawStatus.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return Optional.ofNullable(MAPPING.get(normalized));
    }
}
