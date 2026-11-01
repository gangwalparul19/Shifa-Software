package com.shifa.oms.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Small shared helpers: rounding, percentages and percentiles. */
public final class AnalyticsMath {

    private AnalyticsMath() {
    }

    public static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** {@code part / whole} as a 0-100 percentage rounded to one place; 0 when {@code whole} is not positive. */
    public static double pct(double part, double whole) {
        return whole <= 0 ? 0.0 : round1(part * 100.0 / whole);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Money to two decimals; null counts as zero. */
    public static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code total / count} to two decimals, or zero when there is nothing to divide. */
    public static BigDecimal average(BigDecimal total, long count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return nz(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** The p-th percentile (0-100, nearest rank) of values sorted ascending; null when empty. */
    public static Double percentile(List<Double> sortedAscending, double p) {
        if (sortedAscending.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sortedAscending.size());
        int index = Math.min(sortedAscending.size() - 1, Math.max(0, rank - 1));
        return sortedAscending.get(index);
    }

    /** The median of values sorted ascending; null when empty. */
    public static Double median(List<Double> sortedAscending) {
        int n = sortedAscending.size();
        if (n == 0) {
            return null;
        }
        if (n % 2 == 1) {
            return sortedAscending.get(n / 2);
        }
        return (sortedAscending.get(n / 2 - 1) + sortedAscending.get(n / 2)) / 2.0;
    }

    /** "CUSTOMER_UNAVAILABLE" to "Customer unavailable". */
    public static String humanize(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String lower = code.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
