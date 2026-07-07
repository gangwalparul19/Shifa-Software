/**
 * Coupons / discount codes &amp; offers (Phase D).
 *
 * <p>Provides redeemable discount codes (PERCENT / FLAT / FREE_SHIPPING) with
 * applicability rules (active flag, validity window, minimum cart, total and
 * per-customer usage limits). The financially sensitive applicability + discount
 * math lives in the pure {@link com.shifa.oms.coupon.domain.CouponCalculator}
 * (no Spring / persistence) so it can be unit-tested exhaustively.
 *
 * <p>{@link com.shifa.oms.coupon.CouponService} exposes a public validation path
 * (used by the storefront cart to preview a discount) and an authoritative
 * checkout-time redemption path (re-validated server-side, increments usage
 * respecting limits). {@link com.shifa.oms.coupon.AdminCouponController} offers
 * ADMIN-only CRUD + an active toggle.
 */
package com.shifa.oms.coupon;
