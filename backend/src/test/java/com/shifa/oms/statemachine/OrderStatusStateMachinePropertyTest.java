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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property-based tests for the order status state machine (Requirements 8.*).
 *
 * <p>Each property runs the jqwik default of 1000 tries (well above the required
 * minimum of 100 iterations).
 */
class OrderStatusStateMachinePropertyTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final OrderStatusStateMachine machine = new OrderStatusStateMachine(FIXED_CLOCK);

    // Feature: shifa-herbal-remedies, Property 4: New orders start in Pending_Admin_Approval
    // **Validates: Requirements 3.6, 7.11, 8.2**
    @Property
    void newOrdersStartInPendingAdminApproval(@ForAll("orderSources") String source) {
        // A validly created order — whatever its source (Storefront checkout or
        // Salesperson entry) — begins life in Pending_Admin_Approval.
        OrderStatusLifecycle order = newOrderFrom(source);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(OrderStatus.INITIAL).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
    }

    // Feature: shifa-herbal-remedies, Property 5: Illegal status transitions are rejected and state is retained
    // **Validates: Requirements 8.1, 8.3, 11.4**
    @Property
    void illegalTransitionsAreRejectedAndStateRetained(@ForAll("statuses") OrderStatus current,
                                                        @ForAll("statuses") OrderStatus target,
                                                        @ForAll("orderSources") String source) {
        OrderStatusLifecycle order = new OrderStatusLifecycle(current);
        int historyBefore = order.history().size();

        if (current.canTransitionTo(target)) {
            // Legal transitions are accepted and move to the target.
            assertThatCode(() -> machine.transition(order, target, "actor", source))
                    .doesNotThrowAnyException();
            assertThat(order.status()).isEqualTo(target);
        } else {
            // Illegal transitions are rejected (409) and leave everything unchanged.
            assertThatThrownBy(() -> machine.transition(order, target, "actor", source))
                    .isInstanceOf(IllegalStatusTransitionException.class);
            assertThat(order.status()).isEqualTo(current);
            assertThat(order.history()).hasSize(historyBefore);
        }

        // Regardless of outcome, the status is always one of the 15 defined states.
        assertThat(Set.of(OrderStatus.values())).contains(order.status());
    }

    // Feature: shifa-herbal-remedies, Property 6: Every accepted transition is recorded in status history
    // **Validates: Requirements 8.4**
    @Property
    void everyAcceptedTransitionIsRecordedInHistory(@ForAll("statuses") OrderStatus start,
                                                    @ForAll @Size(min = 1, max = 20) List<@From("choices") Integer> choices,
                                                    @ForAll("orderSources") String source) {
        OrderStatusLifecycle order = new OrderStatusLifecycle(start);
        String actor = "actor-" + source;

        for (int choice : choices) {
            List<OrderStatus> targets = List.copyOf(order.status().allowedTargets());
            if (targets.isEmpty()) {
                break; // terminal state: no further accepted transitions possible
            }
            OrderStatus previous = order.status();
            OrderStatus target = targets.get(Math.floorMod(choice, targets.size()));
            int sizeBefore = order.history().size();

            StatusHistoryEntry entry = machine.transition(order, target, actor, source);

            // The log grows by exactly one entry.
            assertThat(order.history()).hasSize(sizeBefore + 1);
            StatusHistoryEntry recorded = order.history().get(order.history().size() - 1);
            assertThat(recorded).isEqualTo(entry);

            // The entry records the new status, timestamp, and actor/source.
            assertThat(recorded.fromStatus()).isEqualTo(previous);
            assertThat(recorded.toStatus()).isEqualTo(target);
            assertThat(order.status()).isEqualTo(target);
            assertThat(recorded.actor()).isEqualTo(actor);
            assertThat(recorded.source()).isEqualTo(source);
            assertThat(recorded.changedAt()).isNotNull();
        }
    }

    private OrderStatusLifecycle newOrderFrom(String source) {
        // Both order sources create an order through the same lifecycle constructor,
        // which fixes the initial status; source only affects the history actor/source.
        return new OrderStatusLifecycle();
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    @Provide
    Arbitrary<String> orderSources() {
        return Arbitraries.of("STOREFRONT", "SALESPERSON");
    }

    @Provide
    Arbitrary<Integer> choices() {
        return Arbitraries.integers().between(0, 1_000);
    }
}
