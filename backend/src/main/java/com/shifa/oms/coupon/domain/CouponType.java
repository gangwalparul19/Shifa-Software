package com.shifa.oms.coupon.domain;

/**
 * The kind of discount a coupon grants (Phase D: coupons / discount codes).
 *
 * <ul>
 *   <li>{@link #PERCENT} — a percentage off the cart subtotal, optionally capped
 *       by {@code max_discount_amount}.</li>
 *   <li>{@link #FLAT} — a fixed rupee amount off, never exceeding the subtotal.</li>
 *   <li>{@link #FREE_SHIPPING} — waives the shipping fee. The storefront has no
 *       shipping fee at checkout today, so this is treated as a zero monetary
 *       discount while flagging {@code freeShipping=true} in the result, so the
 *       feature is future-ready when shipping fees are introduced.</li>
 * </ul>
 */
public enum CouponType {
    PERCENT,
    FLAT,
    FREE_SHIPPING
}
