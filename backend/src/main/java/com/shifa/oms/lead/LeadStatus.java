package com.shifa.oms.lead;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lead pipeline status (Requirement 2.1; design &sect;3.1, &sect;State
 * Machine). A lead is in exactly one of these states at any time.
 *
 * <p>The allowed source&nbsp;&rarr;&nbsp;target transitions are encoded as an
 * explicit, frozen table (design &sect;State Machine), mirroring
 * {@link com.shifa.oms.statemachine.OrderStatus}. A transition that is not
 * present in the table is illegal and must be rejected, leaving the current
 * status unchanged (Requirement 2.7). The single initial status for every new
 * lead is {@link #NEW} (Requirement 1.1, 2.1).
 *
 * <p>The funnel is forward-only — {@code NEW → CONTACTED → QUOTED} — and a lead
 * may be marked {@link #LOST} from any non-terminal status (requiring a
 * {@link LostReason}). {@link #WON} is <strong>not</strong> a manually selectable
 * target: it is reachable only through the Convert action (Requirement 2.5,
 * 4.2), so it never appears in any state's allowed-target set. {@link #WON} and
 * {@link #LOST} are terminal (Requirement 2.4).
 *
 * <p>This enum only encodes transition <em>legality</em>; scoping, history,
 * audit, and the LOST-reason requirement live in {@code LeadService}.
 */
public enum LeadStatus {

    NEW,
    CONTACTED,
    QUOTED,
    WON,
    LOST;

    /** The status assigned to every newly captured lead (Requirement 1.1, 2.1). */
    public static final LeadStatus INITIAL = NEW;

    /**
     * The explicit, frozen transition table: for each status, the set of statuses
     * it may legally transition to. Terminal states map to an empty set, and
     * {@link #WON} is intentionally absent from every set (Convert-only).
     */
    private static final Map<LeadStatus, Set<LeadStatus>> TRANSITIONS = buildTransitions();

    private static Map<LeadStatus, Set<LeadStatus>> buildTransitions() {
        Map<LeadStatus, Set<LeadStatus>> table = new EnumMap<>(LeadStatus.class);

        // Forward funnel + LOST from any non-terminal status (design §State Machine).
        table.put(NEW, EnumSet.of(CONTACTED, LOST));
        table.put(CONTACTED, EnumSet.of(QUOTED, LOST));
        // QUOTED may only be manually marked LOST; WON is Convert-only (Req 2.5).
        table.put(QUOTED, EnumSet.of(LOST));

        // Terminal states — no outgoing transitions (Req 2.4).
        table.put(WON, EnumSet.noneOf(LeadStatus.class));
        table.put(LOST, EnumSet.noneOf(LeadStatus.class));

        // Freeze the table so it cannot be mutated at runtime.
        Map<LeadStatus, Set<LeadStatus>> frozen = new EnumMap<>(LeadStatus.class);
        for (Map.Entry<LeadStatus, Set<LeadStatus>> e : table.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /** The set of statuses the given status may legally transition to (never null). */
    public static Set<LeadStatus> allowedTargets(LeadStatus from) {
        if (from == null) {
            return Collections.emptySet();
        }
        return TRANSITIONS.getOrDefault(from, Collections.emptySet());
    }

    /**
     * Whether a manual transition from {@code from} to {@code to} is legal (present
     * in the frozen table). {@link #WON} is never legal here (Convert-only).
     */
    public static boolean canTransitionTo(LeadStatus from, LeadStatus to) {
        return to != null && allowedTargets(from).contains(to);
    }

    /** Whether the given status is terminal (no outgoing transitions): WON or LOST. */
    public static boolean isTerminal(LeadStatus status) {
        return status != null && allowedTargets(status).isEmpty();
    }

    /** Instance convenience for {@link #allowedTargets(LeadStatus)}. */
    public Set<LeadStatus> allowedTargets() {
        return allowedTargets(this);
    }

    /** Instance convenience for {@link #canTransitionTo(LeadStatus, LeadStatus)}. */
    public boolean canTransitionTo(LeadStatus to) {
        return canTransitionTo(this, to);
    }

    /** Instance convenience for {@link #isTerminal(LeadStatus)}. */
    public boolean isTerminal() {
        return isTerminal(this);
    }
}
