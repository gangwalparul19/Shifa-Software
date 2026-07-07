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
 *   <li>{@code lost}, {@code damaged}, {@code missing} &rarr; {@code COURIER_LOST} (Req 17.1)</li>
 *   <li>{@code customer_rejected}, {@code refused}, {@code rejected} &rarr; {@code CUSTOMER_REJECTED} (Req 11.1)</li>
 *   <li>{@code delivery_failed}, {@code failed}, {@code undelivered}, {@code attempt_failed} &rarr; {@code DELIVERY_FAILED} (Req 11.2)</li>
 * </ul>
 */
public final class CourierStatusMapper {

    private static final Map<String, OrderStatus> MAPPING = Map.ofEntries(
            Map.entry("pickup", OrderStatus.DISPATCHED),
            Map.entry("picked_up", OrderStatus.DISPATCHED),
            Map.entry("dispatched", OrderStatus.DISPATCHED),
            Map.entry("in_transit", OrderStatus.IN_TRANSIT),
            Map.entry("out_for_delivery", OrderStatus.OUT_FOR_DELIVERY),
            Map.entry("delivered", OrderStatus.DELIVERED),
            Map.entry("return", OrderStatus.RTO),
            Map.entry("returned", OrderStatus.RTO),
            Map.entry("rto", OrderStatus.RTO),
            Map.entry("lost", OrderStatus.COURIER_LOST),
            Map.entry("damaged", OrderStatus.COURIER_LOST),
            Map.entry("missing", OrderStatus.COURIER_LOST),
            // Customer refused at the door (Req 11.1).
            Map.entry("customer_rejected", OrderStatus.CUSTOMER_REJECTED),
            Map.entry("refused", OrderStatus.CUSTOMER_REJECTED),
            Map.entry("rejected", OrderStatus.CUSTOMER_REJECTED),
            // Failed delivery attempt (Req 11.2).
            Map.entry("delivery_failed", OrderStatus.DELIVERY_FAILED),
            Map.entry("failed", OrderStatus.DELIVERY_FAILED),
            Map.entry("undelivered", OrderStatus.DELIVERY_FAILED),
            Map.entry("attempt_failed", OrderStatus.DELIVERY_FAILED));

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
