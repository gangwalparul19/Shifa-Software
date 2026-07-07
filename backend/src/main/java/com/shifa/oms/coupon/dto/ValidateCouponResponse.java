package com.shifa.oms.coupon.dto;

import java.math.BigDecimal;

/**
 * Result of previewing a coupon against a cart (Phase D). On success
 * {@link #valid()} is {@code true}, {@link #discountAmount()} is the money off
 * and {@link #newTotal()} is the discounted subtotal; on failure {@link #valid()}
 * is {@code false} and {@link #message()} explains why (e.g. minimum cart not
 * met, expired, usage limit reached). {@link #freeShipping()} flags a
 * FREE_SHIPPING coupon (future-ready; zero monetary discount today).
 *
 * @param valid          whether the coupon can be applied
 * @param code           the normalized coupon code (upper-cased), echoed back
 * @param discountAmount the money discount (0 when invalid)
 * @param newTotal       the subtotal after the discount (equals the subtotal when invalid)
 * @param freeShipping   whether free shipping applies
 * @param message        a customer-facing status / error message
 */
public record ValidateCouponResponse(
        boolean valid,
        String code,
        BigDecimal discountAmount,
        BigDecimal newTotal,
        boolean freeShipping,
        String message) {
}
