package com.shifa.oms.coupon.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A pure, persistence-free snapshot of a coupon's rules, consumed by the
 * {@link CouponCalculator}. Keeping evaluation decoupled from the JPA
 * {@code Coupon} entity lets the financially sensitive applicability + discount
 * logic be unit-tested exhaustively with plain values (no Spring, no database).
 *
 * <p>Nullable fields express "no constraint": a {@code null} {@code minCartAmount}
 * means no minimum, a {@code null} window bound means open-ended, and a
 * {@code null} limit means unlimited.
 *
 * @param code              the (upper-cased) coupon code, for messages
 * @param type              the discount kind
 * @param value             percent for PERCENT, amount for FLAT, ignored for FREE_SHIPPING
 * @param minCartAmount     minimum cart subtotal required, or {@code null}
 * @param maxDiscountAmount cap on a PERCENT discount, or {@code null}
 * @param active            master on/off toggle
 * @param startsAt          window start (inclusive), or {@code null} for open
 * @param endsAt            window end (inclusive), or {@code null} for open
 * @param usageLimit        total redemptions allowed, or {@code null} for unlimited
 * @param usedCount         redemptions already made
 * @param perCustomerLimit  redemptions allowed per customer, or {@code null}
 */
public record CouponPolicy(
        String code,
        CouponType type,
        BigDecimal value,
        BigDecimal minCartAmount,
        BigDecimal maxDiscountAmount,
        boolean active,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer usageLimit,
        int usedCount,
        Integer perCustomerLimit) {
}
