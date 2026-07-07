package com.shifa.oms.coupon.domain;

import com.shifa.oms.order.domain.Money;

/**
 * Immutable result of evaluating a coupon against a cart subtotal
 * ({@link CouponCalculator#evaluate}).
 *
 * <p>When {@link #valid()} is {@code true} the {@link #discountAmount()} is the
 * money to subtract from the subtotal (already capped so it never exceeds the
 * subtotal, rounded to 2 dp HALF_UP) and {@link #freeShipping()} indicates a
 * FREE_SHIPPING coupon (whose monetary discount is zero today — see
 * {@link CouponType#FREE_SHIPPING}). When invalid, {@link #reason()} carries a
 * customer-facing explanation and the discount is {@link Money#ZERO}.
 *
 * @param valid          whether the coupon can be applied
 * @param reason         the reason it is invalid (null/blank when valid)
 * @param discountAmount the money discount (0 when invalid or FREE_SHIPPING)
 * @param freeShipping   whether free shipping applies (future-ready flag)
 */
public record CouponDiscount(boolean valid, String reason, Money discountAmount, boolean freeShipping) {

    /** A valid result with the given discount and free-shipping flag. */
    public static CouponDiscount valid(Money discountAmount, boolean freeShipping) {
        return new CouponDiscount(true, null, discountAmount, freeShipping);
    }

    /** An invalid result carrying a customer-facing reason; discount is zero. */
    public static CouponDiscount invalid(String reason) {
        return new CouponDiscount(false, reason, Money.ZERO, false);
    }
}
