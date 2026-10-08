package com.shifa.oms.gst.filing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The configurable monetary tolerance, in rupees, within which a reconciliation difference is
 * treated as reconciled (GST returns &amp; filing, Reqs 7.4, 9.1).
 *
 * <p>The tolerance is a scale-2 {@link BigDecimal} constrained to {@code [0.00, 9999.99]}. The
 * {@link #of(BigDecimal)} factory resolves a configured value (e.g.
 * {@code app_settings.gst_reconciliation_tolerance}) to a valid tolerance: it is rounded to two
 * decimal places ({@link RoundingMode#HALF_UP}) and, when {@code null} or outside the supported
 * range, the {@link #DEFAULT default of 1.00} is substituted. A {@code ReconciliationTolerance}
 * therefore always holds a scale-2 value in {@code [0.00, 9999.99]}.
 *
 * <p>Pure and Spring-free; no JPA.
 *
 * @param amount the tolerance in rupees, always scale-2 and within {@code [0.00, 9999.99]}
 */
public record ReconciliationTolerance(BigDecimal amount) {

    /** The smallest supported tolerance, {@code 0.00}. */
    public static final BigDecimal MIN = new BigDecimal("0.00");

    /** The largest supported tolerance, {@code 9999.99}. */
    public static final BigDecimal MAX = new BigDecimal("9999.99");

    /** The default tolerance applied when no valid value is configured (Reqs 7.4, 9.1). */
    public static final BigDecimal DEFAULT = new BigDecimal("1.00");

    /**
     * Normalises the amount to scale 2 ({@link RoundingMode#HALF_UP}) and validates the range
     * invariant (Reqs 7.4, 9.1).
     *
     * @throws NullPointerException     when {@code amount} is {@code null}
     * @throws IllegalArgumentException when the scaled {@code amount} is not in {@code [0.00, 9999.99]}
     */
    public ReconciliationTolerance {
        if (amount == null) {
            throw new NullPointerException("tolerance amount must not be null");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(MIN) < 0 || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(
                    "reconciliation tolerance must be between " + MIN + " and " + MAX + ", was " + amount);
        }
    }

    /**
     * Resolves a configured tolerance to a valid one: rounds to scale 2 ({@link RoundingMode#HALF_UP})
     * and defaults to {@link #DEFAULT 1.00} when the configured value is {@code null} or, once
     * scaled, falls outside {@code [0.00, 9999.99]} (Reqs 7.4, 9.1).
     *
     * @param configured the configured value, or {@code null} when unset
     * @return a valid {@code ReconciliationTolerance} — the scaled configured value when in range,
     *         otherwise the default
     */
    public static ReconciliationTolerance of(BigDecimal configured) {
        if (configured == null) {
            return new ReconciliationTolerance(DEFAULT);
        }
        BigDecimal scaled = configured.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN) < 0 || scaled.compareTo(MAX) > 0) {
            return new ReconciliationTolerance(DEFAULT);
        }
        return new ReconciliationTolerance(scaled);
    }
}
