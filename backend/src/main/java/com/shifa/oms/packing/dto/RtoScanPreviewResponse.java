package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Read-only result of resolving an order-barcode scan on the RTO page (label
 * redesign feature): identifies the order and its current status, and reports
 * whether marking it RTO is currently a legal move — without changing the
 * order. The client must obtain the reason (+ optional note) and explicit
 * confirmation, then invoke {@code POST /api/packing/{id}/rto}; the final
 * request remains the source of truth for authorization and concurrent status
 * changes.
 */
public record RtoScanPreviewResponse(
        String message,
        OrderSummaryResponse order,
        boolean eligible
) {

    /** Statuses from which a manual RTO mark is a legal move (mirrors {@code OrderStatus}'s RTO edges). */
    private static final Set<OrderStatus> RTO_ELIGIBLE_STATUSES = EnumSet.of(
            OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    public static RtoScanPreviewResponse from(OrderEntity order) {
        boolean eligible = RTO_ELIGIBLE_STATUSES.contains(order.getOrderStatus());
        String message = eligible
                ? "Order " + order.getOrderCode() + " can be marked RTO."
                : "Order " + order.getOrderCode() + " cannot be marked RTO (current status "
                        + order.getOrderStatus() + ").";
        return new RtoScanPreviewResponse(message, OrderSummaryResponse.from(order), eligible);
    }
}
