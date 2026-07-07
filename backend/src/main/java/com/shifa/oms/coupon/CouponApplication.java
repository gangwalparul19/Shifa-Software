package com.shifa.oms.coupon;

import com.shifa.oms.order.domain.Money;

/**
 * The authoritative outcome of applying a coupon to an order at checkout
 * (Phase D): the normalized coupon code recorded on the order, the money
 * discount granted (to be subtracted from the subtotal), and whether free
 * shipping applies (future-ready flag).
 *
 * @param couponCode     the upper-cased coupon code stored on the order
 * @param discountAmount the money discount applied
 * @param freeShipping   whether free shipping applies
 */
public record CouponApplication(String couponCode, Money discountAmount, boolean freeShipping) {
}
