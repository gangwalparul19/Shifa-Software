package com.shifa.oms.statemachine;

import java.time.Clock;
import java.util.Objects;

/**
 * Validates and applies order status transitions against the explicit
 * transition table encoded in {@link OrderStatus} (Requirement 8.1, 8.3).
 *
 * <p>A transition is accepted only if it is legal from the order's current
 * status. Illegal transitions are rejected with an
 * {@link IllegalStatusTransitionException} (mapped to HTTP 409) and the order's
 * status is left unchanged (Requirement 8.3, 11.4). Every accepted transition
 * appends exactly one {@link StatusHistoryEntry} recording the from/to status,
 * the actor, the source, and a timestamp (Requirement 8.4).
 *
 * <p>This component is pure domain logic with no persistence or Spring
 * dependencies. Settlement side effects that accompany certain transitions
 * (task&nbsp;5) are layered on separately; this class only governs transition
 * legality and history recording.
 */
public class OrderStatusStateMachine {

    private final Clock clock;

    /** Creates a state machine that timestamps transitions with the system clock. */
    public OrderStatusStateMachine() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a state machine with an injected clock, enabling deterministic
     * timestamps in tests.
     *
     * @param clock the clock used to stamp history entries (never {@code null})
     */
    public OrderStatusStateMachine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Attempts to transition an order to {@code target}.
     *
     * <p>If the transition is legal from the order's current status, the order's
     * status is advanced and exactly one history entry is appended and returned.
     * If it is illegal, an {@link IllegalStatusTransitionException} is thrown and
     * the order is left completely unchanged (status and history both retained).
     *
     * @param order  the order lifecycle to transition (never {@code null})
     * @param target the requested target status
     * @param actor  the user or system principal causing the change
     * @param source the origin of the change (ADMIN/PACKING/COURIER/SYSTEM)
     * @return the single {@link StatusHistoryEntry} recorded for this transition
     * @throws IllegalStatusTransitionException if the transition is not allowed
     */
    public StatusHistoryEntry transition(OrderStatusLifecycle order,
                                         OrderStatus target,
                                         String actor,
                                         String source) {
        Objects.requireNonNull(order, "order");
        OrderStatus current = order.status();
        if (!current.canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(
                    "Illegal order status transition from " + current + " to " + target);
        }
        StatusHistoryEntry entry =
                new StatusHistoryEntry(current, target, actor, source, clock.instant());
        order.apply(target, entry);
        return entry;
    }

    /**
     * Whether the given transition would be accepted, without mutating anything.
     *
     * @param from the current status
     * @param to   the requested target status
     * @return {@code true} if the transition is legal
     */
    public boolean isLegal(OrderStatus from, OrderStatus to) {
        return from != null && from.canTransitionTo(to);
    }
}
