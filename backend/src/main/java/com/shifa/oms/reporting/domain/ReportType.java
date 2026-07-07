package com.shifa.oms.reporting.domain;

/**
 * The report views the {@code Reporting_Service} can generate (Req 20.1, 20.3).
 *
 * <ul>
 *   <li>{@link #DAILY} — one row per order date, with order count and total sales.</li>
 *   <li>{@link #MONTHLY} — one row per calendar month.</li>
 *   <li>{@link #PRODUCT} — one row per product, with quantity sold and sales.</li>
 *   <li>{@link #STATE} — one row per destination state.</li>
 *   <li>{@link #SALESPERSON} — the detailed per-order report (Req 20.3), one row per
 *       order carrying every required column.</li>
 * </ul>
 */
public enum ReportType {
    DAILY,
    MONTHLY,
    PRODUCT,
    STATE,
    SALESPERSON;

    /** Case-insensitive parse of a report type from a request path/param value. */
    public static ReportType from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("report type is required");
        }
        return switch (value.trim().toLowerCase()) {
            case "daily" -> DAILY;
            case "monthly" -> MONTHLY;
            case "product", "product-wise", "productwise" -> PRODUCT;
            case "state", "state-wise", "statewise" -> STATE;
            case "salesperson", "salesperson-wise" -> SALESPERSON;
            default -> throw new IllegalArgumentException("unknown report type: " + value);
        };
    }
}
