package com.shifa.oms.order.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional details captured when an admin approves a pending order: the
 * delivery method to use for this order (in-house-delivery feature follow-up —
 * the admin decides/overrides the delivery partner at approval time, regardless
 * of the default set at order entry). Null/blank leaves the order's current
 * delivery method unchanged. The body is optional — approving with an absent or
 * empty body still approves the order with its existing delivery method.
 */
public record ApproveOrderRequest(
        @Pattern(regexp = "(?i)(QUIKSHIPX|IN_HOUSE)?", message = "deliveryMethod must be QUIKSHIPX or IN_HOUSE")
        String deliveryMethod
) {
}
