package com.shifa.oms.dashboard;

import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the {@code GROUP BY order_status} aggregates the dashboards read into an
 * {@code OrderStatus -> count} map. The role dashboard, the owner snapshot and the
 * metrics service each used to carry their own copy of this loop.
 */
final class OrderStatusCounts {

    private OrderStatusCounts() {
    }

    /**
     * Parses each row's stored status name and sums counts per {@link OrderStatus}.
     * A null name, or a name with no matching enum constant (defensive: the column
     * is a known enum), is skipped so a stray value can never break a dashboard.
     */
    static Map<OrderStatus, Long> fold(List<OrderRepository.StatusCountRow> rows) {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderRepository.StatusCountRow row : rows) {
            if (row.getStatus() == null) {
                continue;
            }
            try {
                counts.merge(OrderStatus.valueOf(row.getStatus()), row.getCount(), Long::sum);
            } catch (IllegalArgumentException ignored) {
                // Unknown status name: skip it.
            }
        }
        return counts;
    }
}
