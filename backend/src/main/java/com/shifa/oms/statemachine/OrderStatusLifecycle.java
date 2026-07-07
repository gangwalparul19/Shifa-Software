package com.shifa.oms.statemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The mutable status lifecycle of a single order: its current
 * {@link OrderStatus} plus the append-only log of {@link StatusHistoryEntry}
 * records describing how it got there (Requirement 8.2, 8.4).
 *
 * <p>This is a pure, persistence-free domain concept so it can be exercised
 * in-memory by property-based tests. Persistence wiring (mapping status and
 * history to the {@code orders} / {@code status_history} tables) is layered on
 * later in task&nbsp;9; the order aggregate can embed or delegate to this type.
 *
 * <p>Status is only ever mutated through {@link OrderStatusStateMachine}, which
 * enforces the transition table before calling {@link #apply}. New orders start
 * in {@link OrderStatus#INITIAL} ({@code Pending_Admin_Approval}).
 */
public final class OrderStatusLifecycle {

    private OrderStatus status;
    private final List<StatusHistoryEntry> history = new ArrayList<>();

    /** Creates a lifecycle for a new order in the initial status. */
    public OrderStatusLifecycle() {
        this(OrderStatus.INITIAL);
    }

    /**
     * Creates a lifecycle starting at an explicit status. Primarily useful for
     * tests that need to exercise transitions from an arbitrary state.
     *
     * @param initial the starting status (never {@code null})
     */
    public OrderStatusLifecycle(OrderStatus initial) {
        this.status = Objects.requireNonNull(initial, "initial");
    }

    /** The current order status (never {@code null}). */
    public OrderStatus status() {
        return status;
    }

    /** An unmodifiable view of the status-history log, in transition order. */
    public List<StatusHistoryEntry> history() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Applies an already-validated transition: advances the status and appends
     * exactly one history entry. Intended to be called only by
     * {@link OrderStatusStateMachine} after the transition has been checked
     * against the transition table.
     *
     * @param target the new status to move to
     * @param entry  the history entry recording this transition
     */
    void apply(OrderStatus target, StatusHistoryEntry entry) {
        this.status = Objects.requireNonNull(target, "target");
        this.history.add(Objects.requireNonNull(entry, "entry"));
    }
}
