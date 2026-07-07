package com.shifa.oms.coupon.dto;

import com.shifa.oms.order.dto.CheckoutRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Public payload for {@code POST /api/checkout/validate-coupon} (Phase D): a
 * coupon code plus the current cart items. The server prices the items (same
 * pricing as checkout) and evaluates the coupon so the storefront can preview
 * the discount before the order is placed. Cart items reuse the checkout item
 * shape so pricing is identical.
 *
 * @param code  the coupon code entered by the customer
 * @param items the cart lines (product id + quantity)
 */
public record ValidateCouponRequest(
        @NotBlank(message = "code is required")
        String code,

        @NotEmpty(message = "cart must contain at least one item")
        @Valid
        List<CheckoutRequest.CheckoutItemRequest> items
) {
}
