package com.shifa.oms.statemachine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle status (Requirement 8.1; design &sect;2.1, &sect;4.1). An
 * order is in exactly one of these states at any time.
 *
 * <p>The allowed source&nbsp;&rarr;&nbsp;target transitions are encoded as an
 * explicit table (design: "Order Status State Machine"). A transition that is
 * not present in the table is illegal and must be rejected, leaving the current
 * status unchanged (Requirement 8.3, 12.3, 12.4). The single initial status for
 * every new order is {@link #PENDING_ADMIN_APPROVAL} (Requirement 8.2, 3.6, 7.11).
 *
 * <p>The role-based-order-workflow feature adds three states:
 * {@link #HANDED_TO_DELIVERY} (a handover step between {@link #PACKED} and
 * {@link #COURIER_ASSIGNED}) and two distinct downstream delivery outcomes,
 * {@link #CUSTOMER_REJECTED} and {@link #DELIVERY_FAILED}. Courier assignment now
 * runs from {@link #HANDED_TO_DELIVERY} (on dispatch), not directly from
 * {@link #PACKED} (design &sect;4.1).
 *
 * <p>This enum only encodes transition <em>legality</em>. Per-transition role
 * authorization lives in {@link TransitionAuthority}; settlement and receivable
 * side effects that accompany certain transitions ({@code Delivered},
 * {@code RTO}, {@code Redispatch}) are implemented separately.
 */
public enum OrderStatus {

    PENDING_ADMIN_APPROVAL,
    APPROVED,
    REJECTED,
    LABEL_GENERATED,
    PACKED,
    HANDED_TO_DELIVERY,
    COURIER_ASSIGNED,
    DISPATCHED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CUSTOMER_REJECTED,
    DELIVERY_FAILED,
    COD_COLLECTED,
    CLOSED,
    RTO,
    REDISPATCH,
    CANCELLED;

    /** The status assigned to every newly created order (Requirement 8.2). */
    public static final OrderStatus INITIAL = PENDING_ADMIN_APPROVAL;

    /**
     * The explicit transition table: for each status, the set of statuses it may
     * legally transition to. Terminal states map to an empty set.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = buildTransitions();

    private static Map<OrderStatus, Set<OrderStatus>> buildTransitions() {
        Map<OrderStatus, Set<OrderStatus>> table = new EnumMap<>(OrderStatus.class);

        // Admin approval outcomes (Req 9.3, 9.4).
        table.put(PENDING_ADMIN_APPROVAL, EnumSet.of(APPROVED, REJECTED, CANCELLED));
        // Label service generates the internal label (Req 10.3).
        table.put(APPROVED, EnumSet.of(LABEL_GENERATED));
        // Packing barcode scan (Req 8.2). QuikShipX orders instead fast-forward
        // straight to Courier_Assigned when a tracking id is allotted at approval
        // (SYSTEM only), skipping the manual pack/handover/dispatch steps.
        table.put(LABEL_GENERATED, EnumSet.of(PACKED, COURIER_ASSIGNED));
        // Handover to the delivery courier (Req 9.2, 9.3). Courier assignment no
        // longer runs directly from Packed — it moves to the handover step.
        table.put(PACKED, EnumSet.of(HANDED_TO_DELIVERY));
        // Dispatch enqueues courier assignment (Req 9.5, 10.1); a failed/retried
        // assignment self-retains Handed_To_Delivery (Req 10.4).
        table.put(HANDED_TO_DELIVERY, EnumSet.of(COURIER_ASSIGNED, HANDED_TO_DELIVERY));
        // Pickup (Req 10.2) + forward courier progressions so a QuikShipX tracking
        // poll never stalls when an intermediate scan (e.g. picked-up) is skipped
        // between polls — all SYSTEM-driven.
        table.put(COURIER_ASSIGNED, EnumSet.of(
                DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RTO, REDISPATCH));
        // Courier webhook/tracking progressions (Req 10.3) + a direct Delivered
        // for a skipped in-transit scan.
        table.put(DISPATCHED, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RTO, REDISPATCH));
        table.put(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, RTO, REDISPATCH));
        // New delivery outcomes Customer_Rejected / Delivery_Failed (Req 11.1, 11.2).
        table.put(OUT_FOR_DELIVERY,
                EnumSet.of(DELIVERED, CUSTOMER_REJECTED, DELIVERY_FAILED, RTO, REDISPATCH));
        // Settlement outcomes (Req 16.1, 16.2).
        table.put(DELIVERED, EnumSet.of(CLOSED, COD_COLLECTED));

        // Terminal states — no outgoing transitions (Req 12.7).
        table.put(COD_COLLECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(CLOSED, EnumSet.noneOf(OrderStatus.class));
        table.put(REJECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        table.put(CUSTOMER_REJECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(DELIVERY_FAILED, EnumSet.noneOf(OrderStatus.class));
        table.put(RTO, EnumSet.noneOf(OrderStatus.class));
        table.put(REDISPATCH, EnumSet.noneOf(OrderStatus.class));

        // Freeze the table so it cannot be mutated at runtime.
        Map<OrderStatus, Set<OrderStatus>> frozen = new EnumMap<>(OrderStatus.class);
        for (Map.Entry<OrderStatus, Set<OrderStatus>> e : table.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** The set of statuses this status may legally transition to (never null). */
    public Set<OrderStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet());
    }

    /**
     * Whether a transition from this status to {@code target} is legal.
     *
     * @param target the requested target status
     * @return {@code true} if the transition is present in the transition table
     */
    public boolean canTransitionTo(OrderStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    /** Whether this is a terminal status (no outgoing transitions). */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
