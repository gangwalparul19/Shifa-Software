package com.shifa.oms.order;

import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit tests for {@link OrderStatusGroup} — the mapping that backs the
 * grouped status filter on the admin Orders page ({@code ?statusGroup=} →
 * {@code orderStatus IN (members)}). These pin down exactly which raw statuses
 * each business stage expands to, and prove the groups form a complete,
 * non-overlapping partition so no status is ever unreachable or double-counted.
 */
class OrderStatusGroupTest {

    // --- Partition guarantees ----------------------------------------------

    @Test
    void everyOrderStatusBelongsToExactlyOneGroup() {
        List<OrderStatus> all = new ArrayList<>();
        for (OrderStatusGroup group : OrderStatusGroup.values()) {
            all.addAll(group.statuses());
        }

        // No status appears in two groups.
        assertThat(all).doesNotHaveDuplicates();
        // Every lifecycle status is covered by some group (complete partition).
        assertThat(EnumSet.copyOf(all)).isEqualTo(EnumSet.allOf(OrderStatus.class));
        // Sanity: total membership count equals the number of statuses.
        assertThat(all).hasSize(OrderStatus.values().length);
    }

    @Test
    void noGroupIsEmpty() {
        for (OrderStatusGroup group : OrderStatusGroup.values()) {
            assertThat(group.statuses())
                    .as("group %s must have at least one status", group)
                    .isNotEmpty();
        }
    }

    // --- Membership pins (workflow-critical: matches the packing queue) ------

    @Test
    void singleStatusGroupsMapToTheirStatus() {
        assertThat(OrderStatusGroup.PENDING_APPROVAL.statuses())
                .containsExactly(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(OrderStatusGroup.PACKAGING.statuses())
                .containsExactly(OrderStatus.APPROVED);
        assertThat(OrderStatusGroup.LABEL_GENERATED.statuses())
                .containsExactly(OrderStatus.LABEL_GENERATED);
    }

    @Test
    void packedIsAwaitingHandoverAndHandedIsAwaitingDispatch() {
        // Mirrors the packing queue terminology: PACKED = "awaiting handover",
        // HANDED_TO_DELIVERY = "awaiting dispatch" (project memory: packing queue).
        assertThat(OrderStatusGroup.AWAITING_HANDOVER.statuses())
                .containsExactly(OrderStatus.PACKED);
        assertThat(OrderStatusGroup.AWAITING_DISPATCH.statuses())
                .containsExactly(OrderStatus.HANDED_TO_DELIVERY);
    }

    @Test
    void inTransitClubsTheCourierStages() {
        assertThat(OrderStatusGroup.IN_TRANSIT.statuses())
                .containsExactlyInAnyOrder(
                        OrderStatus.COURIER_ASSIGNED,
                        OrderStatus.DISPATCHED,
                        OrderStatus.IN_TRANSIT,
                        OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void completedClubsTheSuccessfulTerminalStates() {
        assertThat(OrderStatusGroup.COMPLETED.statuses())
                .containsExactlyInAnyOrder(
                        OrderStatus.DELIVERED,
                        OrderStatus.COD_COLLECTED,
                        OrderStatus.CLOSED);
    }

    @Test
    void cancelledAndFailedReturnedSplitThePreAndPostShipFailures() {
        assertThat(OrderStatusGroup.CANCELLED.statuses())
                .containsExactlyInAnyOrder(OrderStatus.REJECTED, OrderStatus.CANCELLED);
        assertThat(OrderStatusGroup.FAILED_RETURNED.statuses())
                .containsExactlyInAnyOrder(
                        OrderStatus.CUSTOMER_REJECTED,
                        OrderStatus.DELIVERY_FAILED,
                        OrderStatus.RTO,
                        OrderStatus.COURIER_LOST);
    }

    // --- The set a group filter expands to ----------------------------------

    @Test
    void groupMembersAreUsableAsAnInFilterSet() {
        // What OrderListSpecifications passes to `orderStatus IN (...)`.
        Set<OrderStatus> inTransit = EnumSet.copyOf(OrderStatusGroup.IN_TRANSIT.statuses());
        assertThat(inTransit).contains(OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY);
        assertThat(inTransit).doesNotContain(OrderStatus.DELIVERED, OrderStatus.PACKED);
    }
}
