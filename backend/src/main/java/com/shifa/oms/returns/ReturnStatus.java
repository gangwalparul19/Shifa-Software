package com.shifa.oms.returns;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle status of an {@link OrderReturn} ("operations depth" Feature 1).
 *
 * <p>The happy path is {@link #REQUESTED} &rarr; {@link #APPROVED} &rarr;
 * {@link #REFUNDED}; a request may instead be {@link #REJECTED} (terminal). The
 * allowed source&nbsp;&rarr;&nbsp;target transitions are encoded as an explicit
 * table (mirroring {@code OrderStatus}); a transition not present in the table is
 * illegal and must be rejected, leaving the current status unchanged.
 */
public enum ReturnStatus {

    REQUESTED,
    APPROVED,
    REFUNDED,
    REJECTED;

    /** The status assigned to every newly created return. */
    public static final ReturnStatus INITIAL = REQUESTED;

    private static final Map<ReturnStatus, Set<ReturnStatus>> TRANSITIONS = buildTransitions();

    private static Map<ReturnStatus, Set<ReturnStatus>> buildTransitions() {
        Map<ReturnStatus, Set<ReturnStatus>> table = new EnumMap<>(ReturnStatus.class);
        // A requested return is approved or rejected by the admin.
        table.put(REQUESTED, EnumSet.of(APPROVED, REJECTED));
        // An approved return is marked refunded once the money is returned.
        table.put(APPROVED, EnumSet.of(REFUNDED));
        // Terminal states — no outgoing transitions.
        table.put(REFUNDED, EnumSet.noneOf(ReturnStatus.class));
        table.put(REJECTED, EnumSet.noneOf(ReturnStatus.class));

        Map<ReturnStatus, Set<ReturnStatus>> frozen = new EnumMap<>(ReturnStatus.class);
        for (Map.Entry<ReturnStatus, Set<ReturnStatus>> e : table.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** The set of statuses this status may legally transition to (never null). */
    public Set<ReturnStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet());
    }

    /** Whether a transition from this status to {@code target} is legal. */
    public boolean canTransitionTo(ReturnStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    /** Whether this is a terminal status (no outgoing transitions). */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
