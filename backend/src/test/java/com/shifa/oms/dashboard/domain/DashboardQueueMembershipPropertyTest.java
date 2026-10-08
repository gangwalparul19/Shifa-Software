package com.shifa.oms.dashboard.domain;

import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the per-role dashboard work-queue classification
 * (design §6.7, §Correctness Properties (13), §10.1).
 *
 * Feature: role-based-order-workflow, Property 13: A queue contains exactly the
 * orders in its status set.
 *
 * The classification is pure over {@link DashboardQueue}, so this test exercises
 * it in-memory with no Spring/DB and no Mockito mocks of concrete classes.
 *
 * **Validates: Requirements 6.1, 8.1, 9.4**
 */
class DashboardQueueMembershipPropertyTest {

    /** A minimal order projection: an id plus its lifecycle status. */
    record Order(long id, OrderStatus status) {
    }

    // Feature: role-based-order-workflow, Property 13: A queue contains exactly the orders in its status set
    // **Validates: Requirements 6.1, 8.1, 9.4**
    @Property(tries = 200)
    void eachQueueContainsExactlyItsStatusSet(
            @ForAll @Size(max = 60) List<@From("orders") Order> orders) {

        for (DashboardQueue queue : DashboardQueue.values()) {
            // The reference set: exactly the orders whose status is in the queue's set.
            List<Order> expected = new ArrayList<>();
            for (Order o : orders) {
                if (queue.statuses().contains(o.status())) {
                    expected.add(o);
                }
            }

            List<Order> actual = queue.filter(orders, Order::status);

            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(queue.count(orders, Order::status)).isEqualTo(expected.size());
            // No order outside the status set leaks in.
            assertThat(actual).allMatch(o -> queue.statuses().contains(o.status()));
        }
    }

    // Feature: role-based-order-workflow, Property 13: A queue contains exactly the orders in its status set
    // **Validates: Requirements 6.1, 8.1, 9.4**
    @Property(tries = 200)
    void queuesMapToTheirSpecifiedStatusSets(@ForAll OrderStatus status) {
        assertThat(DashboardQueue.APPROVAL.contains(status))
                .isEqualTo(status == OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(DashboardQueue.PACKING.contains(status))
                .isEqualTo(status == OrderStatus.APPROVED || status == OrderStatus.LABEL_GENERATED);
        assertThat(DashboardQueue.AWAITING_HANDOVER.contains(status))
                .isEqualTo(status == OrderStatus.PACKED);
        assertThat(DashboardQueue.AWAITING_DISPATCH.contains(status))
                .isEqualTo(status == OrderStatus.HANDED_TO_DELIVERY);
    }

    @Provide
    Arbitrary<Order> orders() {
        Arbitrary<Long> id = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<OrderStatus> status = Arbitraries.of(OrderStatus.values());
        return Combinators.combine(id, status).as(Order::new);
    }
}
