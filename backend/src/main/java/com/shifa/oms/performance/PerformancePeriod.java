package com.shifa.oms.performance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Business periods available to the Salesperson 360 exports. Dates are resolved
 * in the OMS business zone so a browser or server running in another timezone
 * cannot move an order across a period boundary.
 */
public enum PerformancePeriod {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    WEEK("This Week"),
    MONTH("This Month"),
    OVERALL("Overall");

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final String label;

    PerformancePeriod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Returns the half-open [from, to) window for the supplied business date. */
    public Window window(LocalDate today) {
        LocalDate from;
        LocalDate to = today.plusDays(1);
        switch (this) {
            case TODAY -> from = today;
            case YESTERDAY -> {
                from = today.minusDays(1);
                to = today;
            }
            case WEEK -> {
                from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            }
            case MONTH -> from = today.withDayOfMonth(1);
            case OVERALL -> {
                return new Window(null, null);
            }
            default -> throw new IllegalStateException("Unhandled performance period: " + this);
        }
        return new Window(from.atStartOfDay(), to.atStartOfDay());
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
                            + " (expected TODAY, YESTERDAY, WEEK, MONTH or OVERALL)");
        }
    }

    public record Window(LocalDateTime from, LocalDateTime to) {
        public boolean contains(LocalDateTime timestamp) {
            return timestamp != null
                    && (from == null || !timestamp.isBefore(from))
                    && (to == null || timestamp.isBefore(to));
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
