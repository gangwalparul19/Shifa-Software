package com.shifa.oms.coupon.domain;

import com.shifa.oms.order.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Pure coupon applicability + discount logic (Phase D). Intentionally free of
 * Spring and persistence so it can be exercised exhaustively in isolation and
 * reused by both the public validation endpoint and authoritative
 * checkout-time redemption.
 *
 * <p>Evaluation runs the applicability gates in a fixed order — active, validity
 * window, total usage limit, per-customer limit, minimum cart — and returns the
 * first failure as an invalid result. When all gates pass the discount is
 * computed by {@link CouponType}:
 * <ul>
 *   <li>{@code PERCENT}: {@code subtotal × value / 100}, capped at
 *       {@code maxDiscountAmount} when set.</li>
 *   <li>{@code FLAT}: {@code min(value, subtotal)}.</li>
 *   <li>{@code FREE_SHIPPING}: {@code 0} monetary discount, {@code freeShipping=true}
 *       (there is no shipping fee at checkout today; the flag keeps the feature
 *       future-ready).</li>
 * </ul>
 *
 * <p>In every case the discount is clamped to never exceed the subtotal and is
 * rounded to 2 decimals HALF_UP (via {@link Money}).
 */
public final class CouponCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CouponCalculator() {
    }

    /**
     * Evaluates a coupon against a cart subtotal at a point in time, without a
     * per-customer usage count (treated as zero — no per-customer redemptions
     * yet). Suitable for anonymous validation.
     */
    public static CouponDiscount evaluate(CouponPolicy policy, Money subtotal, LocalDateTime now) {
        return evaluate(policy, subtotal, now, 0L);
    }

    /**
     * Evaluates a coupon against a cart subtotal at a point in time, given how
     * many times this customer has already redeemed it.
     *
     * @param policy              the coupon rules (non-null)
     * @param subtotal            the cart subtotal (non-null, non-negative)
     * @param now                 the evaluation instant (non-null)
     * @param customerUsageCount  prior redemptions by this customer (>= 0)
     * @return a valid result with the computed discount, or an invalid result
     *         with a customer-facing reason
     */
    public static CouponDiscount evaluate(CouponPolicy policy, Money subtotal,
                                          LocalDateTime now, long customerUsageCount) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(subtotal, "subtotal");
        Objects.requireNonNull(now, "now");

        if (!policy.active()) {
            return CouponDiscount.invalid("This coupon is not active.");
        }
        if (policy.startsAt() != null && now.isBefore(policy.startsAt())) {
            return CouponDiscount.invalid("This coupon is not valid yet.");
        }
        if (policy.endsAt() != null && now.isAfter(policy.endsAt())) {
            return CouponDiscount.invalid("This coupon has expired.");
        }
        if (policy.usageLimit() != null && policy.usedCount() >= policy.usageLimit()) {
            return CouponDiscount.invalid("This coupon has reached its usage limit.");
        }
        if (policy.perCustomerLimit() != null && customerUsageCount >= policy.perCustomerLimit()) {
            return CouponDiscount.invalid("You have already used this coupon.");
        }
        if (policy.minCartAmount() != null) {
            Money min = Money.of(policy.minCartAmount());
            if (subtotal.compareTo(min) < 0) {
                return CouponDiscount.invalid(
                        "A minimum cart amount of " + min + " is required to use this coupon.");
            }
        }

        Money discount = computeDiscount(policy, subtotal);
        boolean freeShipping = policy.type() == CouponType.FREE_SHIPPING;
        return CouponDiscount.valid(discount, freeShipping);
    }

    /** Computes the raw discount by type, clamped to the subtotal. Never negative. */
    private static Money computeDiscount(CouponPolicy policy, Money subtotal) {
        Money discount;
        switch (policy.type()) {
            case PERCENT -> {
                BigDecimal percent = policy.value() != null ? policy.value() : BigDecimal.ZERO;
                BigDecimal raw = subtotal.toBigDecimal()
                        .multiply(percent)
                        .divide(HUNDRED, Money.SCALE, RoundingMode.HALF_UP);
                discount = Money.of(raw);
                if (policy.maxDiscountAmount() != null) {
                    discount = min(discount, Money.of(policy.maxDiscountAmount()));
                }
            }
            case FLAT -> {
                BigDecimal value = policy.value() != null ? policy.value() : BigDecimal.ZERO;
                discount = Money.of(value);
            }
            case FREE_SHIPPING -> discount = Money.ZERO;
            default -> discount = Money.ZERO;
        }
        // Never allow a negative discount, and never discount more than the subtotal.
        if (discount.isNegative()) {
            discount = Money.ZERO;
        }
        return min(discount, subtotal);
    }

    private static Money min(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
