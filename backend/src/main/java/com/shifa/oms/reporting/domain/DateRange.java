package com.shifa.oms.reporting.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An inclusive custom date window [{@code from}, {@code to}] used to restrict a
 * report to the orders whose order date falls within it (Req 20.2).
 *
 * <p>Either bound may be {@code null} to leave that side unbounded: a
 * {@code null} {@code from} means "from the beginning of time", a {@code null}
 * {@code to} means "until now". This keeps the pure windowing logic simple and
 * lets the API accept optional {@code from}/{@code to} query params.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from date must not be after to date");
        }
    }

    /** An unbounded range that contains every date. */
    public static DateRange all() {
        return new DateRange(null, null);
    }

    /** A closed range with both bounds present. */
    public static DateRange of(LocalDate from, LocalDate to) {
        return new DateRange(from, to);
    }

    /**
     * Whether {@code date} falls within this window (inclusive of both bounds).
     * A {@code null} date is never contained.
     */
    public boolean contains(LocalDate date) {
        if (date == null) {
            return false;
        }
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    /**
     * The window of equal length immediately preceding this one, used for
     * previous-period comparison (Req 19.4). Only defined when both bounds are
     * present; returns {@code null} otherwise.
     */
    public DateRange previousPeriod() {
        if (from == null || to == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        return new DateRange(prevFrom, prevTo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DateRange other)) {
            return false;
        }
        return Objects.equals(from, other.from) && Objects.equals(to, other.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }
}
