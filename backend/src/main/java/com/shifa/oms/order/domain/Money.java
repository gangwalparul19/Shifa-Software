package com.shifa.oms.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary value backed by {@link BigDecimal} with a fixed scale of 2
 * and {@link RoundingMode#HALF_UP}. All payment/COD arithmetic in the domain
 * core uses this type so there is no floating-point drift (design: "Monetary
 * values stored as DECIMAL(12,2)").
 *
 * <p>Designed to be reused by the order status state machine (task 4) and the
 * settlement / receivables ledger (task 5).
 */
public final class Money implements Comparable<Money> {

    /** Fixed decimal scale for all money values. */
    public static final int SCALE = 2;

    /** Rounding applied when normalizing to {@link #SCALE}. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Zero rupees. */
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal raw) {
        this.amount = raw.setScale(SCALE, ROUNDING);
    }

    /** Creates a {@code Money} from a {@link BigDecimal}, normalizing scale. */
    public static Money of(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        return new Money(value);
    }

    /** Creates a {@code Money} from a decimal string, e.g. {@code "12.50"}. */
    public static Money of(String value) {
        Objects.requireNonNull(value, "value");
        return new Money(new BigDecimal(value));
    }

    /** Creates a {@code Money} from a whole-rupee amount. */
    public static Money of(long wholeRupees) {
        return new Money(BigDecimal.valueOf(wholeRupees));
    }

    /** Creates a {@code Money} from a count of paise (1/100 of a rupee). */
    public static Money ofCents(long paise) {
        return new Money(BigDecimal.valueOf(paise, SCALE));
    }

    /** Returns the sum of this value and {@code other}. */
    public Money add(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(this.amount.add(other.amount));
    }

    /** Returns this value minus {@code other}. */
    public Money subtract(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(this.amount.subtract(other.amount));
    }

    /** Returns this value multiplied by an integer quantity. */
    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)));
    }

    /** The underlying normalized {@link BigDecimal} (scale 2). */
    public BigDecimal toBigDecimal() {
        return amount;
    }

    /** True when the value is exactly zero. */
    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** True when the value is strictly less than zero. */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        // amount is always normalized to SCALE, so equals is well-defined.
        return this.amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        // amount is always normalized to SCALE, so two equal values share the
        // same unscaled value and scale, keeping hashCode consistent with equals.
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
