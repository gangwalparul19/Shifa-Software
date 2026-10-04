package com.shifa.oms.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Admin "save delivery method" payload for
 * {@code PUT /api/admin/orders/{id}/delivery-method} (change-delivery-method
 * feature): lets an admin set/change the order's delivery partner
 * ({@code QUIKSHIPX} or {@code IN_HOUSE}) and persist it WITHOUT approving the
 * order.
 *
 * <p>Previously the only way to persist a delivery-method choice was through the
 * approve action (which also triggers the label + QuikShipX pipeline), so an
 * admin could not simply correct the delivery method on a still-pending order.
 * This endpoint changes only the delivery method and leaves the order's
 * lifecycle status untouched.
 */
public record UpdateDeliveryMethodRequest(
        @NotBlank(message = "deliveryMethod is required")
        @Pattern(regexp = "(?i)(QUIKSHIPX|IN_HOUSE)",
                message = "deliveryMethod must be QUIKSHIPX or IN_HOUSE")
        String deliveryMethod
) {
}
