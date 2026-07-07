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

    /** One row of a grouped-count report: a group key label and its order count (Req 16.1&ndash;16.3). */
    public record CountRow(String key, long orderCount) {
    }

    /**
     * The delivery-outcome summary over a range (Req 16.4): the counts of the
     * four terminal outcomes (kept separate) plus the delivery success rate,
     * expressed as a percentage {@code DELIVERED / (DELIVERED + CUSTOMER_REJECTED
     * + DELIVERY_FAILED + CANCELLED) * 100} (0 when the denominator is 0).
     */
    public record DeliveryOutcome(long delivered, long customerRejected, long deliveryFailed,
                                  long cancelled, BigDecimal successRate) {
    }
}
