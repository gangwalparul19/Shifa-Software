package com.shifa.oms.statemachine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle status (Requirement 8.1). An order is in exactly one of
 * these 15 states at any time.
 *
 * <p>The allowed source&nbsp;&rarr;&nbsp;target transitions are encoded as an
 * explicit table (design: "Order Status State Machine"). A transition that is
 * not present in the table is illegal and must be rejected, leaving the current
 * status unchanged (Requirement 8.3). The single initial status for every new
 * order is {@link #PENDING_ADMIN_APPROVAL} (Requirement 8.2, 3.6, 7.11).
 *
 * <p>This enum only encodes transition <em>legality</em>. Settlement and
 * receivable side effects that accompany certain transitions
 * ({@code Delivered}, {@code RTO}, {@code Courier_Lost}) are implemented
 * separately in task&nbsp;5.
 */
public enum OrderStatus {

    PENDING_ADMIN_APPROVAL,
    APPROVED,
    REJECTED,
    LABEL_GENERATED,
    PACKED,
    COURIER_ASSIGNED,
    DISPATCHED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COD_COLLECTED,
    CLOSED,
    RTO,
    COURIER_LOST,
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
        // Packing barcode scan (Req 11.1).
        table.put(LABEL_GENERATED, EnumSet.of(PACKED));
        // Courier assignment succeeds, or errors and retains Packed (Req 12.2, 12.4).
        table.put(PACKED, EnumSet.of(COURIER_ASSIGNED, PACKED));
        // Pickup (Req 13.1).
        table.put(COURIER_ASSIGNED, EnumSet.of(DISPATCHED));
        // Courier webhook progressions (Req 13.2, 17.1).
        table.put(DISPATCHED, EnumSet.of(IN_TRANSIT, OUT_FOR_DELIVERY, RTO, COURIER_LOST));
        table.put(IN_TRANSIT, EnumSet.of(OUT_FOR_DELIVERY, DELIVERED, RTO, COURIER_LOST));
        table.put(OUT_FOR_DELIVERY, EnumSet.of(DELIVERED, RTO, COURIER_LOST));
        // Settlement outcomes (Req 16.1, 16.2).
        table.put(DELIVERED, EnumSet.of(CLOSED, COD_COLLECTED));

        // Terminal states — no outgoing transitions.
        table.put(COD_COLLECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(CLOSED, EnumSet.noneOf(OrderStatus.class));
        table.put(REJECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        table.put(RTO, EnumSet.noneOf(OrderStatus.class));
        table.put(COURIER_LOST, EnumSet.noneOf(OrderStatus.class));

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
