package com.shifa.oms.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The percentage change of a metric relative to the previous period (Req 19.4).
 *
 * <p>The rule (design "Correctness Properties", Property 23) is:
 * <ul>
 *   <li>when both current and previous are zero, the change is {@code 0} (applicable);</li>
 *   <li>when previous is zero and current is non-zero, the change is
 *       <em>not applicable</em> (an increase from nothing has no finite percentage);</li>
 *   <li>otherwise the change is {@code (current − previous) / previous × 100}.</li>
 * </ul>
 */
public record PercentChange(boolean applicable, BigDecimal value) {

    /** A not-applicable change (previous period was zero but current is non-zero). */
    public static final PercentChange NOT_APPLICABLE = new PercentChange(false, null);

    /**
     * Computes the percentage change of {@code current} relative to
     * {@code previous} following the rule above.
     */
    public static PercentChange of(BigDecimal current, BigDecimal previous) {
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        BigDecimal prev = previous == null ? BigDecimal.ZERO : previous;
        boolean curZero = cur.compareTo(BigDecimal.ZERO) == 0;
        boolean prevZero = prev.compareTo(BigDecimal.ZERO) == 0;
        if (prevZero && curZero) {
            return new PercentChange(true, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        if (prevZero) {
            return NOT_APPLICABLE;
        }
        BigDecimal change = cur.subtract(prev)
                .divide(prev, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        return new PercentChange(true, change);
    }
}
