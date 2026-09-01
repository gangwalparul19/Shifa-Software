package com.shifa.oms.statemachine;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the order lifecycle state machine after the
 * role-based-order-workflow extension (design &sect;2.1, &sect;4.1).
 *
 * <p>These properties are pure and in-memory: they exercise {@link OrderStatus}
 * and a random walk over its transition table with no Spring, database, or
 * Mockito mocks of concrete classes. Each {@code @Property} runs the jqwik
 * default of 1000 tries, well above the required minimum of 100 iterations.
 *
 * <p>Property 3 validates {@link OrderStatus} against an <em>independent</em>
 * copy of the design &sect;4.1 transition table declared in this test, so the
 * production table cannot silently drift from the specification.
 */
class OrderStatusTransitionTablePropertyTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final OrderStatusStateMachine machine = new OrderStatusStateMachine(FIXED_CLOCK);

    /**
     * The design &sect;4.1 legal-transition table, restated independently of the
     * production code so the property genuinely checks conformance to the spec.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = expectedTable();

    /** The terminal states of the lifecycle (design &sect;4.1). */
    private static final Set<OrderStatus> TERMINAL = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.CANCELLED,
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO, OrderStatus.REDISPATCH,
            OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    private static Map<OrderStatus, Set<OrderStatus>> expectedTable() {
        Map<OrderStatus, Set<OrderStatus>> t = new EnumMap<>(OrderStatus.class);
        t.put(OrderStatus.PENDING_ADMIN_APPROVAL,
                EnumSet.of(OrderStatus.APPROVED, OrderStatus.REJECTED, OrderStatus.CANCELLED));
        t.put(OrderStatus.APPROVED, EnumSet.of(OrderStatus.LABEL_GENERATED));
        t.put(OrderStatus.LABEL_GENERATED,
                EnumSet.of(OrderStatus.PACKED, OrderStatus.COURIER_ASSIGNED));
        t.put(OrderStatus.PACKED, EnumSet.of(OrderStatus.HANDED_TO_DELIVERY));
        t.put(OrderStatus.HANDED_TO_DELIVERY,
                EnumSet.of(OrderStatus.COURIER_ASSIGNED, OrderStatus.HANDED_TO_DELIVERY));
        t.put(OrderStatus.COURIER_ASSIGNED, EnumSet.of(OrderStatus.DISPATCHED,
                OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED,
                OrderStatus.RTO, OrderStatus.REDISPATCH));
        t.put(OrderStatus.DISPATCHED, EnumSet.of(OrderStatus.IN_TRANSIT,
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED,
                OrderStatus.RTO, OrderStatus.REDISPATCH));
        t.put(OrderStatus.IN_TRANSIT, EnumSet.of(OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED, OrderStatus.RTO, OrderStatus.REDISPATCH));
        t.put(OrderStatus.OUT_FOR_DELIVERY, EnumSet.of(OrderStatus.DELIVERED,
                OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
                OrderStatus.RTO, OrderStatus.REDISPATCH));
        t.put(OrderStatus.DELIVERED, EnumSet.of(OrderStatus.CLOSED, OrderStatus.COD_COLLECTED));
        // Terminal states.
        t.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.CUSTOMER_REJECTED, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.DELIVERY_FAILED, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.RTO, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.REDISPATCH, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.COD_COLLECTED, EnumSet.noneOf(OrderStatus.class));
        t.put(OrderStatus.CLOSED, EnumSet.noneOf(OrderStatus.class));
        return t;
    }

    // Feature: role-based-order-workflow, Property 1: An order is always in exactly one status
    // **Validates: Requirements 12.1**
    @Property
    void anOrderIsAlwaysInExactlyOneStatus(@ForAll("statuses") OrderStatus start,
                                           @ForAll @Size(min = 0, max = 30)
                                           List<@From("choices") Integer> choices) {
        OrderStatusLifecycle order = new OrderStatusLifecycle(start);

        // A freshly created lifecycle holds exactly one well-defined status.
        assertExactlyOneStatus(order);

        // After any random walk over the legal table, it still holds exactly one.
        for (int choice : choices) {
            List<OrderStatus> targets = List.copyOf(order.status().allowedTargets());
            if (targets.isEmpty()) {
                break;
            }
            OrderStatus target = targets.get(Math.floorMod(choice, targets.size()));
            machine.transition(order, target, "actor", "TEST");
            assertExactlyOneStatus(order);
        }
    }

    // Feature: role-based-order-workflow, Property 3: Transition legality matches the specified table
    // **Validates: Requirements 6.5, 8.3, 10.5, 11.8, 12.3, 12.4**
    @Property
    void transitionLegalityMatchesTheSpecifiedTable(@ForAll("statuses") OrderStatus from,
                                                    @ForAll("statuses") OrderStatus to) {
        Set<OrderStatus> expectedTargets = EXPECTED.get(from);

        // canTransitionTo agrees with the independent §4.1 table for every pair.
        assertThat(from.canTransitionTo(to)).isEqualTo(expectedTargets.contains(to));

        // allowedTargets() reproduces the specified target set exactly.
        assertThat(from.allowedTargets()).isEqualTo(expectedTargets);

        // The state machine accepts a pair iff the table permits it.
        assertThat(machine.isLegal(from, to)).isEqualTo(expectedTargets.contains(to));
    }

    // Feature: role-based-order-workflow, Property 6: Terminal states have no outgoing transitions
    // **Validates: Requirements 12.7**
    @Property
    void terminalStatesHaveNoOutgoingTransitions(@ForAll("statuses") OrderStatus status,
                                                 @ForAll("statuses") OrderStatus target) {
        boolean terminal = TERMINAL.contains(status);

        // isTerminal() classifies exactly the design §4.1 terminal set.
        assertThat(status.isTerminal()).isEqualTo(terminal);

        if (terminal) {
            // No outgoing transition is legal from a terminal state, for any target.
            assertThat(status.allowedTargets()).isEmpty();
            assertThat(status.canTransitionTo(target)).isFalse();
        } else {
            // Non-terminal states always have at least one legal outgoing target.
            assertThat(status.allowedTargets()).isNotEmpty();
        }
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    @Provide
    Arbitrary<Integer> choices() {
        return Arbitraries.integers().between(0, 1_000);
    }

    private static void assertExactlyOneStatus(OrderStatusLifecycle order) {
        OrderStatus current = order.status();
        assertThat(current).isNotNull();
        // Exactly one enum value equals the current status.
        long matches = EnumSet.allOf(OrderStatus.class).stream()
                .filter(s -> s == current).count();
        assertThat(matches).isEqualTo(1L);
    }
}
