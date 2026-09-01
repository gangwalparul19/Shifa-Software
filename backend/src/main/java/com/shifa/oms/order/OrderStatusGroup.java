package com.shifa.oms.order;

import com.shifa.oms.statemachine.OrderStatus;

import java.util.List;

/**
 * Coarse, business-facing lifecycle groups that club the many fine-grained
 * {@link OrderStatus} values into a handful of stages the team actually thinks
 * in (product feedback: 25 staff, ~20 less-technical salespeople found the raw
 * status list overwhelming). Backs the grouped status filter on the admin
 * Orders page ({@code GET /api/admin/orders?statusGroup=}).
 *
 * <p>Every {@link OrderStatus} belongs to exactly one group, so the groups are a
 * complete, non-overlapping partition of the lifecycle. Filtering by a group
 * expands to {@code orderStatus IN (members)} in {@link OrderListSpecifications}.
 * This is a presentation/filter grouping only — it does NOT change the stored
 * per-order status or the state machine.
 */
public enum OrderStatusGroup {

    /** Awaiting admin approval — the very first stage. */
    PENDING_APPROVAL(OrderStatus.PENDING_ADMIN_APPROVAL),

    /**
     * Approved and being prepared — approval, internal label, and (for the manual
     * flow) packing/handover. With QuikShip these are skipped in seconds; clubbing
     * them keeps the salesperson's view simple (previously 4 separate stages).
     */
    PROCESSING(
            OrderStatus.APPROVED,
            OrderStatus.LABEL_GENERATED,
            OrderStatus.PACKED,
            OrderStatus.HANDED_TO_DELIVERY),

    /** With the courier — tracking id assigned → dispatched → in transit → out for delivery. */
    SHIPPED(
            OrderStatus.COURIER_ASSIGNED,
            OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT,
            OrderStatus.OUT_FOR_DELIVERY),

    /** Successfully concluded (delivered / COD collected / closed). */
    DELIVERED(
            OrderStatus.DELIVERED,
            OrderStatus.COD_COLLECTED,
            OrderStatus.CLOSED),

    /** Failed or returned after shipping (customer rejected / failed / RTO / redispatch). */
    FAILED_RETURNED(
            OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO,
            OrderStatus.REDISPATCH),

    /** Cancelled or rejected before shipping. */
    CANCELLED(
            OrderStatus.REJECTED,
            OrderStatus.CANCELLED);

    private final List<OrderStatus> statuses;

    OrderStatusGroup(OrderStatus... statuses) {
        this.statuses = List.of(statuses);
    }

    /** The order statuses that make up this group (never empty). */
    public List<OrderStatus> statuses() {
        return statuses;
    }

    /**
     * Leniently resolves a {@code ?statusGroup=} value to a group, tolerating the
     * pre-collapse keys so stale saved views / deep links (e.g. {@code PACKAGING},
     * {@code COMPLETED}, {@code IN_TRANSIT}) keep working. Unknown/blank → null
     * (no group filter), so the Orders page never errors on an old value.
     */
    public static OrderStatusGroup from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "PENDING_APPROVAL" -> PENDING_APPROVAL;
            // Pre-collapse stages that are now folded into PROCESSING.
            case "PROCESSING", "PACKAGING", "LABEL_GENERATED", "AWAITING_HANDOVER", "AWAITING_DISPATCH" -> PROCESSING;
            case "SHIPPED", "IN_TRANSIT" -> SHIPPED;
            case "DELIVERED", "COMPLETED" -> DELIVERED;
            case "FAILED_RETURNED" -> FAILED_RETURNED;
            case "CANCELLED" -> CANCELLED;
            default -> null;
        };
    }
}
