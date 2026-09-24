package com.shifa.oms.packing.dto;

import com.shifa.oms.statemachine.OrderStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for the multi-select in-house dispatch status update
 * ({@code POST /api/packing/dispatch/bulk-status}).
 *
 * <p>Sets {@code status} on every order in {@code ids} that is an in-house
 * delivery (courier-partner orders are tracked by the partner and are skipped).
 * The set of statuses a caller may pick mirrors the order-detail "Update status"
 * dropdown (Out_For_Delivery / Delivered / Dispatched / In_Transit /
 * Customer_Rejected / Delivery_Failed); the per-order service enforces which are
 * actually legal from each order's current status and the in-house-only rule.
 *
 * @param ids    the order ids to update (at least one)
 * @param status the delivery status to set on each
 * @param note   optional note; required by the per-order primitive for the
 *               failure outcomes (Customer_Rejected / Delivery_Failed)
 */
public record BulkDeliveryStatusRequest(
        @NotEmpty(message = "Select at least one order.")
        List<Long> ids,

        @NotNull(message = "A target status is required.")
        OrderStatus status,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {
}
