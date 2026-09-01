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
    void pendingApprovalIsTheSingleFirstStage() {
        assertThat(OrderStatusGroup.PENDING_APPROVAL.statuses())
                .containsExactly(OrderStatus.PENDING_ADMIN_APPROVAL);
    }

    @Test
    void processingClubsApprovalThroughHandover() {
        // The QuikShip-aligned collapse: approval, internal label, and the (skipped
        // for QuikShip) pack/handover steps are one "Processing" stage.
        assertThat(OrderStatusGroup.PROCESSING.statuses())
                .containsExactlyInAnyOrder(
                        OrderStatus.APPROVED,
                        OrderStatus.LABEL_GENERATED,
                        OrderStatus.PACKED,
                        OrderStatus.HANDED_TO_DELIVERY);
    }

    @Test
    void shippedClubsTheCourierStages() {
        assertThat(OrderStatusGroup.SHIPPED.statuses())
                .containsExactlyInAnyOrder(
                        OrderStatus.COURIER_ASSIGNED,
                        OrderStatus.DISPATCHED,
                        OrderStatus.IN_TRANSIT,
                        OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void deliveredClubsTheSuccessfulTerminalStates() {
        assertThat(OrderStatusGroup.DELIVERED.statuses())
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
                        OrderStatus.REDISPATCH);
    }

    // --- The set a group filter expands to ----------------------------------

    @Test
    void groupMembersAreUsableAsAnInFilterSet() {
        // What OrderListSpecifications passes to `orderStatus IN (...)`.
        Set<OrderStatus> shipped = EnumSet.copyOf(OrderStatusGroup.SHIPPED.statuses());
        assertThat(shipped).contains(OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY);
        assertThat(shipped).doesNotContain(OrderStatus.DELIVERED, OrderStatus.PACKED);
    }

    // --- Lenient parsing (stale pre-collapse keys still resolve) -------------

    @Test
    void fromToleratesPreCollapseKeys() {
        assertThat(OrderStatusGroup.from("PACKAGING")).isEqualTo(OrderStatusGroup.PROCESSING);
        assertThat(OrderStatusGroup.from("LABEL_GENERATED")).isEqualTo(OrderStatusGroup.PROCESSING);
        assertThat(OrderStatusGroup.from("AWAITING_HANDOVER")).isEqualTo(OrderStatusGroup.PROCESSING);
        assertThat(OrderStatusGroup.from("AWAITING_DISPATCH")).isEqualTo(OrderStatusGroup.PROCESSING);
        assertThat(OrderStatusGroup.from("IN_TRANSIT")).isEqualTo(OrderStatusGroup.SHIPPED);
        assertThat(OrderStatusGroup.from("COMPLETED")).isEqualTo(OrderStatusGroup.DELIVERED);
        assertThat(OrderStatusGroup.from("SHIPPED")).isEqualTo(OrderStatusGroup.SHIPPED);
        assertThat(OrderStatusGroup.from("unknown")).isNull();
        assertThat(OrderStatusGroup.from(null)).isNull();
        assertThat(OrderStatusGroup.from("  ")).isNull();
    }
}
