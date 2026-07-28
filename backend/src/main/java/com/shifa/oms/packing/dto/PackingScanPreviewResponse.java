package com.shifa.oms.packing.dto;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;

/**
 * Read-only result of resolving an internal-label barcode before a packing move.
 * The client must obtain user confirmation, then invoke the matching mutating
 * endpoint; the final request remains the source of truth for authorization and
 * concurrent status changes.
 */
public record PackingScanPreviewResponse(
        String message,
        OrderSummaryResponse order,
        PackingNextAction nextAction,
        OrderStatus nextStatus
) {

    public static PackingScanPreviewResponse from(OrderEntity order) {
        PackingNextAction action = PackingNextAction.forCurrentStatus(order.getOrderStatus());
        String message = switch (action) {
            case PACK -> "Ready to mark order " + order.getOrderCode() + " Packed.";
            case HANDOVER -> "Order " + order.getOrderCode() + " is ready for handover.";
            case DISPATCH -> "Order " + order.getOrderCode() + " is ready to dispatch.";
            case NONE -> "No packing move is available for order " + order.getOrderCode() + ".";
        };
        return new PackingScanPreviewResponse(
                message,
                OrderSummaryResponse.from(order),
                action,
                action.targetStatus());
    }
}
