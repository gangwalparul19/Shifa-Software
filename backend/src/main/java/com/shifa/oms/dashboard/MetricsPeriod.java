package com.shifa.oms.dashboard;

import com.shifa.oms.reporting.domain.DateRange;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * The dashboard time-period filters (Req 19.2): the fixed presets plus a custom
 * range. Each preset resolves to an inclusive {@link DateRange} relative to a
 * reference "today", so the metrics service and sales graph reuse the same pure
 * windowing the reporting module already validates (Property 23).
 *
 * <p>The custom range is not resolved here — the controller supplies explicit
 * {@code from}/{@code to} bounds for {@link #CUSTOM}; every other preset derives
 * its window from {@code today}.
 */
public enum MetricsPeriod {

    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    THIS_MONTH,
    LAST_MONTH,
    QUARTERLY,
    YEARLY,
    CUSTOM;

    /** The default preset when none is supplied. */
    public static final MetricsPeriod DEFAULT = LAST_30_DAYS;

    /**
     * Parses a period name leniently (case-insensitive, hyphen/space tolerant),
     * falling back to {@link #DEFAULT} for a blank value and throwing for an
     * unknown one.
     */
    public static MetricsPeriod from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "TODAY" -> TODAY;
            case "YESTERDAY" -> YESTERDAY;
            case "LAST_7_DAYS", "LAST7DAYS", "WEEK" -> LAST_7_DAYS;
            case "LAST_30_DAYS", "LAST30DAYS" -> LAST_30_DAYS;
            case "THIS_MONTH", "MONTH" -> THIS_MONTH;
            case "LAST_MONTH" -> LAST_MONTH;
            case "QUARTERLY", "QUARTER" -> QUARTERLY;
            case "YEARLY", "YEAR" -> YEARLY;
            case "CUSTOM" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown period: " + raw);
        };
    }

    /**
     * Resolves this preset to an inclusive window relative to {@code today}.
     *
     * <p>For {@link #CUSTOM} the supplied {@code from}/{@code to} are used
     * verbatim (both must be present); a missing bound defaults that side to the
     * start of the current year / today so the window is always bounded.
     */
    public DateRange resolve(LocalDate today, LocalDate from, LocalDate to) {
        return switch (this) {
            case TODAY -> new DateRange(today, today);
            case YESTERDAY -> {
                LocalDate y = today.minusDays(1);
                yield new DateRange(y, y);
            }
            case LAST_7_DAYS -> new DateRange(today.minusDays(6), today);
            case LAST_30_DAYS -> new DateRange(today.minusDays(29), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today);
            case LAST_MONTH -> {
                LocalDate firstOfThis = today.withDayOfMonth(1);
                LocalDate firstOfLast = firstOfThis.minusMonths(1);
                yield new DateRange(firstOfLast, firstOfThis.minusDays(1));
            }
            case QUARTERLY -> new DateRange(firstDayOfQuarter(today), today);
            case YEARLY -> new DateRange(today.withDayOfYear(1), today);
            case CUSTOM -> new DateRange(
                    from != null ? from : today.withDayOfYear(1),
                    to != null ? to : today);
        };
    }

    /**
     * The natural bucket granularity for the sales graph of this period: daily
     * for short windows, weekly for a quarter, monthly for a year (Req 19.4).
     * The client may still override this.
     */
    public SalesBucket defaultBucket() {
        return switch (this) {
            case TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH -> SalesBucket.DAY;
            case QUARTERLY -> SalesBucket.WEEK;
            case YEARLY -> SalesBucket.MONTH;
            case CUSTOM -> SalesBucket.DAY;
        };
    }

    private static LocalDate firstDayOfQuarter(LocalDate date) {
        int quarter = date.get(IsoFields.QUARTER_OF_YEAR);
        int firstMonth = (quarter - 1) * 3 + 1;
        return LocalDate.of(date.getYear(), firstMonth, 1);
    }

    /** Convenience for the week-bucket helper: the Monday that starts {@code date}'s week. */
    static LocalDate startOfWeek(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }
}
