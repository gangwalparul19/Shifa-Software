package com.shifa.oms.dashboard;

import java.util.Locale;

/**
 * The bucket granularity of the dashboard sales graph (Req 19.4): day-wise,
 * week-wise, or monthly.
 */
public enum SalesBucket {

    DAY,
    WEEK,
    MONTH;

    /** Parses a bucket name leniently, defaulting to {@code null} for a blank value. */
    public static SalesBucket from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DAY", "DAILY", "DAY_WISE" -> DAY;
            case "WEEK", "WEEKLY", "WEEK_WISE" -> WEEK;
            case "MONTH", "MONTHLY" -> MONTH;
            default -> throw new IllegalArgumentException("Unknown bucket: " + raw);
        };
    }
}
