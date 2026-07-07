package com.shifa.oms.coupon.domain;

import com.shifa.oms.order.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based unit tests for the pure {@link CouponCalculator} (Phase D),
 * covering the discount math and every applicability gate: percent (+ cap),
 * flat (capped at the subtotal), free-shipping flag, minimum-cart-not-met,
 * inactive, not-yet-started / expired windows, total usage limit, and
 * per-customer limit.
 */
class CouponCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 12, 0);

    private CouponPolicy policy(CouponType type, BigDecimal value, BigDecimal minCart,
                                BigDecimal maxDiscount, boolean active,
                                LocalDateTime startsAt, LocalDateTime endsAt,
                                Integer usageLimit, int usedCount, Integer perCustomerLimit) {
        return new CouponPolicy("SAVE", type, value, minCart, maxDiscount, active,
                startsAt, endsAt, usageLimit, usedCount, perCustomerLimit);
    }

    // --- PERCENT ------------------------------------------------------------

    @Test
    void percentComputesDiscountRoundedHalfUp() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("999.99"), NOW);

        assertThat(result.valid()).isTrue();
        // 999.99 * 10% = 99.999 -> 100.00 (HALF_UP)
        assertThat(result.discountAmount()).isEqualTo(Money.of("100.00"));
        assertThat(result.freeShipping()).isFalse();
    }

    @Test
    void percentIsCappedAtMaxDiscount() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("50.00"),
                null, new BigDecimal("100.00"), true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("1000.00"), NOW);

        // 50% of 1000 = 500, capped at 100.
        assertThat(result.valid()).isTrue();
        assertThat(result.discountAmount()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void percentNeverExceedsSubtotal() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("100.00"),
                null, null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("250.00"), NOW);

        assertThat(result.discountAmount()).isEqualTo(Money.of("250.00"));
    }

    // --- FLAT ---------------------------------------------------------------

    @Test
    void flatDiscountSubtractsFixedAmount() {
        CouponPolicy p = policy(CouponType.FLAT, new BigDecimal("150.00"),
                null, null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW);

        assertThat(result.valid()).isTrue();
        assertThat(result.discountAmount()).isEqualTo(Money.of("150.00"));
    }

    @Test
    void flatDiscountIsCappedAtSubtotal() {
        CouponPolicy p = policy(CouponType.FLAT, new BigDecimal("500.00"),
                null, null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("300.00"), NOW);

        // Flat 500 but subtotal only 300 -> discount capped at 300 (never negative total).
        assertThat(result.discountAmount()).isEqualTo(Money.of("300.00"));
    }

    // --- FREE_SHIPPING ------------------------------------------------------

    @Test
    void freeShippingIsZeroMonetaryDiscountButFlagged() {
        CouponPolicy p = policy(CouponType.FREE_SHIPPING, BigDecimal.ZERO,
                null, null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("400.00"), NOW);

        assertThat(result.valid()).isTrue();
        assertThat(result.discountAmount()).isEqualTo(Money.ZERO);
        assertThat(result.freeShipping()).isTrue();
    }

    // --- Applicability gates ------------------------------------------------

    @Test
    void minCartNotMetIsInvalid() {
        CouponPolicy p = policy(CouponType.FLAT, new BigDecimal("50.00"),
                new BigDecimal("500.00"), null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("300.00"), NOW);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("minimum cart amount");
        assertThat(result.discountAmount()).isEqualTo(Money.ZERO);
    }

    @Test
    void minCartExactlyMetIsValid() {
        CouponPolicy p = policy(CouponType.FLAT, new BigDecimal("50.00"),
                new BigDecimal("300.00"), null, true, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("300.00"), NOW);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void inactiveCouponIsInvalid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, false, null, null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("not active");
    }

    @Test
    void notYetStartedCouponIsInvalid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, true, NOW.plusDays(1), null, null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("not valid yet");
    }

    @Test
    void expiredCouponIsInvalid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, true, null, NOW.minusDays(1), null, 0, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("expired");
    }

    @Test
    void usageLimitExceededIsInvalid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, true, null, null, 5, 5, null);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("usage limit");
    }

    @Test
    void perCustomerLimitExceededIsInvalid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("10.00"),
                null, null, true, null, null, null, 0, 1);

        // This customer has already redeemed it once, per-customer limit is 1.
        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("500.00"), NOW, 1L);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("already used");
    }

    @Test
    void withinWindowAndUnderLimitsIsValid() {
        CouponPolicy p = policy(CouponType.PERCENT, new BigDecimal("20.00"),
                new BigDecimal("100.00"), new BigDecimal("500.00"), true,
                NOW.minusDays(1), NOW.plusDays(1), 100, 3, 2);

        CouponDiscount result = CouponCalculator.evaluate(p, Money.of("400.00"), NOW, 1L);

        assertThat(result.valid()).isTrue();
        // 20% of 400 = 80 (below the 500 cap).
        assertThat(result.discountAmount()).isEqualTo(Money.of("80.00"));
    }
}
