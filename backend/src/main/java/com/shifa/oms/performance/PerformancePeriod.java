package com.shifa.oms.performance;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ValidationException;
import org.springframework.http.HttpStatus;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Business periods shared by the Salesperson 360 screens (the Salespeople directory and drawer,
 * the sales leaderboard) and their exports. Dates are resolved in the OMS business zone so a
 * browser or server running in another timezone cannot move an order across a period boundary.
 *
 * <p>Every named period is a half-open window {@code [from, to)}. {@link #CUSTOM} takes the two
 * calendar days the caller chose (both included) through
 * {@link #resolve(LocalDate, LocalDate, LocalDate)}; {@link #OVERALL} has no bounds.
 */
public enum PerformancePeriod {

    TODAY("Today"),
    YESTERDAY("Yesterday"),
    /** The last seven calendar days, today included. */
    LAST_7_DAYS("Last 7 Days"),
    /** The current calendar week, Monday to today. */
    WEEK("This Week"),
    MONTH("This Month"),
    /** The whole previous calendar month. */
    LAST_MONTH("Last Month"),
    OVERALL("Overall"),
    /** A caller-chosen range of days, both ends included. */
    CUSTOM("Custom");

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final String label;

    PerformancePeriod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Returns the half-open [from, to) window for the supplied business date. {@link #CUSTOM}
     * has no window without the chosen days: use {@link #resolve(LocalDate, LocalDate, LocalDate)}.
     */
    public Window window(LocalDate today) {
        LocalDate from;
        LocalDate to = today.plusDays(1);
        switch (this) {
            case TODAY -> from = today;
            case YESTERDAY -> {
                from = today.minusDays(1);
                to = today;
            }
            case LAST_7_DAYS -> from = today.minusDays(6);
            case WEEK -> from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> from = today.withDayOfMonth(1);
            case LAST_MONTH -> {
                to = today.withDayOfMonth(1);
                from = to.minusMonths(1);
            }
            case OVERALL -> {
                return new Window(null, null);
            }
            case CUSTOM -> throw new ValidationException("Choose both a start date and an end date.");
            default -> throw new IllegalStateException("Unhandled performance period: " + this);
        }
        return new Window(from.atStartOfDay(), to.atStartOfDay());
    }

    /**
     * The window for this period, using {@code customFrom} / {@code customTo} (both included) when
     * the period is {@link #CUSTOM} and ignoring them otherwise.
     *
     * @throws ValidationException when a custom range is missing a date or ends before it starts
     */
    public Window resolve(LocalDate today, LocalDate customFrom, LocalDate customTo) {
        if (this != CUSTOM) {
            return window(today);
        }
        if (customFrom == null || customTo == null) {
            throw new ValidationException("Choose both a start date and an end date.");
        }
        if (customTo.isBefore(customFrom)) {
            throw new ValidationException("The start date must not be after the end date.");
        }
        return new Window(customFrom.atStartOfDay(), customTo.plusDays(1).atStartOfDay());
    }

    /** Parses a request value; blank means {@link #MONTH}. Unknown values are rejected as HTTP 400. */
    public static PerformancePeriod parse(String value) {
        try {
            return from(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERFORMANCE_PERIOD", e.getMessage());
        }
    }

    public static PerformancePeriod from(String value) {
        if (value == null || value.isBlank()) {
            return MONTH;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown performance period: " + value
                            + " (expected TODAY, YESTERDAY, LAST_7_DAYS, WEEK, MONTH, LAST_MONTH, OVERALL or CUSTOM)");
        }
    }

    public record Window(LocalDateTime from, LocalDateTime to) {
        public boolean contains(LocalDateTime timestamp) {
            return timestamp != null
                    && (from == null || !timestamp.isBefore(from))
                    && (to == null || timestamp.isBefore(to));
        }

        /** True when the window has no bounds ("Overall"). */
        public boolean unbounded() {
            return from == null && to == null;
        }

        /** First calendar day covered, or {@code null} for an unbounded ("Overall") window. */
        public LocalDate firstDay() {
            return from == null ? null : from.toLocalDate();
        }

        /** Last calendar day covered (inclusive; {@code to} is exclusive), or {@code null} when unbounded. */
        public LocalDate lastDay() {
            return to == null ? null : to.toLocalDate().minusDays(1);
        }

        /** Number of calendar days covered, or {@code -1} when unbounded. */
        public long days() {
            if (unbounded()) {
                return -1;
            }
            return java.time.temporal.ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate());
        }

        public String label() {
            if (from == null && to == null) {
                return "All time";
            }
            LocalDate first = from.toLocalDate();
            LocalDate last = to.toLocalDate().minusDays(1);
            if (first.equals(last)) {
                return first.toString();
            }
            return first + " to " + last;
        }
    }
}
