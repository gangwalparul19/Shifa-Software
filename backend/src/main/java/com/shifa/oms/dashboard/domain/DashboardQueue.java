package com.shifa.oms.dashboard.domain;

import com.shifa.oms.statemachine.OrderStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The per-role operational work queues shown on the dashboards (design §6.7,
 * Req 3.3, 3.4, 6.1, 8.1, 9.4). Each queue is defined purely by the set of
 * {@link OrderStatus} values whose orders belong to it, so membership is a pure,
 * table-driven classification with no persistence or web concerns — which is
 * what lets Property 13 exercise it directly.
 *
 * <ul>
 *   <li>{@link #APPROVAL} — orders awaiting admin approval ({@code PENDING_ADMIN_APPROVAL}).</li>
 *   <li>{@link #PACKING} — approved orders awaiting packing ({@code APPROVED},
 *       {@code LABEL_GENERATED}).</li>
 *   <li>{@link #AWAITING_HANDOVER} — packed orders awaiting handover ({@code PACKED}).</li>
 *   <li>{@link #AWAITING_DISPATCH} — handed-over orders awaiting dispatch
 *       ({@code HANDED_TO_DELIVERY}).</li>
 * </ul>
 */
public enum DashboardQueue {

    APPROVAL(EnumSet.of(OrderStatus.PENDING_ADMIN_APPROVAL)),
    PACKING(EnumSet.of(OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED)),
    AWAITING_HANDOVER(EnumSet.of(OrderStatus.PACKED)),
    AWAITING_DISPATCH(EnumSet.of(OrderStatus.HANDED_TO_DELIVERY));

    private final Set<OrderStatus> statuses;

    DashboardQueue(Set<OrderStatus> statuses) {
        this.statuses = Collections.unmodifiableSet(statuses);
    }

    /** The set of order statuses that belong to this queue (never null). */
    public Set<OrderStatus> statuses() {
        return statuses;
    }

    /** Whether an order in {@code status} belongs to this queue. */
    public boolean contains(OrderStatus status) {
        return status != null && statuses.contains(status);
    }

    /**
     * The items whose status belongs to this queue, preserving input order.
     *
     * @param items    the candidate items
     * @param statusOf extracts the {@link OrderStatus} from an item
     * @return exactly the items whose status is in this queue's status set
     */
    public <T> List<T> filter(List<T> items, Function<T, OrderStatus> statusOf) {
        return items.stream().filter(item -> contains(statusOf.apply(item))).toList();
    }

    /** The number of items whose status belongs to this queue. */
    public <T> long count(List<T> items, Function<T, OrderStatus> statusOf) {
        return items.stream().filter(item -> contains(statusOf.apply(item))).count();
    }
}
