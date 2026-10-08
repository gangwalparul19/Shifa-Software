package com.shifa.oms.gst.filing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The pure reconciliation arithmetic every compared figure uses (GST returns &amp; filing,
 * Reqs 7.1, 7.3, 7.4, 7.5, 8.1, 8.2, 8.3, 8.6, 9.2, 9.6).
 *
 * <p>All monetary work is {@link BigDecimal} scale-2 {@code HALF_UP}. The class is stateless,
 * Spring-free and JPA-free, so it is trivially unit- and property-testable. The sign convention is
 * fixed: a difference is always {@code returnValue − ledgerValue}, so a positive difference means
 * the return figure exceeds the ledger/statement figure ({@link DifferenceDirection}).
 */
public final class ReconciliationMath {

    private static final int MONEY_SCALE = 2;

    private ReconciliationMath() {
    }

    /**
     * The signed reconciliation difference: {@code returnValue − ledgerValue}, rounded to scale-2
     * {@code HALF_UP} (Reqs 7.3, 8.1–8.4).
     *
     * @param returnValue the figure the returns system presents
     * @param ledgerValue the corresponding ledger/statement figure
     * @return the scale-2 signed difference
     */
    public static BigDecimal difference(BigDecimal returnValue, BigDecimal ledgerValue) {
        Objects.requireNonNull(returnValue, "returnValue");
        Objects.requireNonNull(ledgerValue, "ledgerValue");
        return scale2(returnValue.subtract(ledgerValue));
    }

    /**
     * Classifies a reconciliation difference by sign (Reqs 7.3, 7.4):
     * {@link DifferenceDirection#RETURN_OVER_LEDGER} when positive,
     * {@link DifferenceDirection#LEDGER_OVER_RETURN} when negative, and
     * {@link DifferenceDirection#EQUAL} when zero.
     *
     * @param difference the signed difference (e.g. from {@link #difference(BigDecimal, BigDecimal)})
     * @return the direction of the difference
     */
    public static DifferenceDirection direction(BigDecimal difference) {
        Objects.requireNonNull(difference, "difference");
        int sign = difference.signum();
        if (sign > 0) {
            return DifferenceDirection.RETURN_OVER_LEDGER;
        }
        if (sign < 0) {
            return DifferenceDirection.LEDGER_OVER_RETURN;
        }
        return DifferenceDirection.EQUAL;
    }

    /**
     * Whether a difference is within the configured tolerance: {@code |difference| ≤ tolerance}
     * (Reqs 7.4, 7.5, 8.6). The comparison is done at scale-2 so that boundary values (e.g. a
     * difference exactly equal to the tolerance) reconcile.
     *
     * @param difference the signed difference
     * @param tolerance  the non-negative reconciliation tolerance
     * @return {@code true} when the absolute difference does not exceed the tolerance
     */
    public static boolean reconciled(BigDecimal difference, BigDecimal tolerance) {
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(tolerance, "tolerance");
        return scale2(difference.abs()).compareTo(scale2(tolerance)) <= 0;
    }

    /**
     * Floors a value at zero: {@code max(value, 0)}, used for net-payable / net-liability display
     * (Req 8.3). The result is scale-2.
     *
     * @param value the value to floor
     * @return {@code value} when positive, otherwise {@code 0.00}
     */
    public static BigDecimal floorZero(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        BigDecimal scaled = scale2(value);
        return scaled.signum() > 0 ? scaled : scale2(BigDecimal.ZERO);
    }

    /**
     * Builds a fully-derived {@link ReconciliationFigure} bundling the difference, direction, and
     * reconciled flag for a compared pair (Reqs 7.1, 9.2).
     *
     * @param label       a human-readable name for the compared figure
     * @param returnValue the figure the returns system presents
     * @param ledgerValue the corresponding ledger/statement figure
     * @param tolerance   the non-negative reconciliation tolerance
     * @return a reconciliation figure whose derived fields are consistent with the arithmetic
     */
    public static ReconciliationFigure figure(
            String label, BigDecimal returnValue, BigDecimal ledgerValue, BigDecimal tolerance) {
        BigDecimal diff = difference(returnValue, ledgerValue);
        return new ReconciliationFigure(
                label,
                scale2(returnValue),
                scale2(ledgerValue),
                diff,
                direction(diff),
                reconciled(diff, tolerance));
    }

    /**
     * Whether an entire return period is reconciled: {@code true} iff every figure in the list is
     * {@link ReconciliationFigure#reconciled() reconciled} (Reqs 9.2, 9.6). An empty list is
     * vacuously reconciled.
     *
     * @param figures the compared figures for a period
     * @return {@code true} when every figure is reconciled
     */
    public static boolean periodReconciled(List<ReconciliationFigure> figures) {
        Objects.requireNonNull(figures, "figures");
        return figures.stream().allMatch(figure -> figure != null && figure.reconciled());
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
