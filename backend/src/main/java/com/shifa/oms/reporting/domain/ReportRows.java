package com.shifa.oms.reporting.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The aggregated row shapes produced by the {@link ReportAggregator} for the
 * grouped report views (Req 20.1). Each is an immutable value holding a group
 * key plus the count and total-sales aggregation for that group.
 */
public final class ReportRows {

    private ReportRows() {
    }

    /** One row of the daily report: a date, its order count, and total sales (Req 20.1). */
    public record DailyRow(LocalDate date, long orderCount, BigDecimal totalSales) {
    }

    /** One row of the monthly report: a month, its order count, and total sales (Req 20.1). */
    public record MonthlyRow(YearMonth month, long orderCount, BigDecimal totalSales) {
    }

    /** One row of the product-wise report: product name, quantity sold, total sales (Req 20.1). */
    public record ProductRow(String productName, long quantity, BigDecimal totalSales) {
    }

    /** One row of the state-wise report: destination state, order count, total sales (Req 20.1). */
    public record StateRow(String state, long orderCount, BigDecimal totalSales) {
    }
}
