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

    /** Approved and being prepared for packing. */
    PACKAGING(OrderStatus.APPROVED),

    /** Shipping label generated, waiting to be packed (packing queue: "to pack"). */
    LABEL_GENERATED(OrderStatus.LABEL_GENERATED),

    /** Packed, waiting to be handed to the delivery partner (packing queue: "awaiting handover"). */
    AWAITING_HANDOVER(OrderStatus.PACKED),

    /** Handed over to delivery, awaiting courier dispatch (packing queue: "awaiting dispatch"). */
    AWAITING_DISPATCH(OrderStatus.HANDED_TO_DELIVERY),

    /** With the courier / tracking service (assigned → out for delivery). */
    IN_TRANSIT(
            OrderStatus.COURIER_ASSIGNED,
            OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT,
            OrderStatus.OUT_FOR_DELIVERY),

    /** Successfully concluded (delivered / COD collected / closed). */
    COMPLETED(
            OrderStatus.DELIVERED,
            OrderStatus.COD_COLLECTED,
            OrderStatus.CLOSED),

    /** Cancelled or rejected before shipping. */
    CANCELLED(
            OrderStatus.REJECTED,
            OrderStatus.CANCELLED),

    /** Failed or returned after shipping (customer rejected / failed / RTO / lost). */
    FAILED_RETURNED(
            OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO,
            OrderStatus.REDISPATCH);

    private final List<OrderStatus> statuses;

    OrderStatusGroup(OrderStatus... statuses) {
        this.statuses = List.of(statuses);
    }

    /** The order statuses that make up this group (never empty). */
    public List<OrderStatus> statuses() {
        return statuses;
    }
}
