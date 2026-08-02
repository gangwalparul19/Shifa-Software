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

    /**
     * One row of the customer-wise report: the customer (name + mobile), how many
     * orders they placed in the window, and the total sales they contributed.
     */
    public record CustomerRow(String customerName, String customerMobile,
                              long orderCount, BigDecimal totalSales) {
    }

    /** One row of a grouped-count report: a group key label and its order count (Req 16.1&ndash;16.3). */
    public record CountRow(String key, long orderCount) {
    }

    /**
     * One row of the channel report (spec {@code shopify-quikshipx-order-sync}, Req 12.1):
     * the order channel, how many orders it produced in the window, the revenue it earned
     * and how many of its orders were delivered.
     *
     * <p>Three measures rather than a plain count, because the point of the report is to
     * compare the channels on value and reliability, not just volume: Shopify could be half
     * the orders and a fifth of the revenue, and a count alone would hide that.
     *
     * @param channel        the canonical channel name, {@code SHOPIFY_API} or {@code SHIFA_ADMIN}
     * @param orderCount     every order in the window, including rejected and cancelled ones
     * @param revenue        the order totals excluding {@code REJECTED} and {@code CANCELLED},
     *                       matching the revenue rule used everywhere else (Req 12.6)
     * @param deliveredCount orders at {@code DELIVERED}, {@code COD_COLLECTED} or {@code CLOSED}
     */
    public record ChannelRow(String channel, long orderCount, BigDecimal revenue, long deliveredCount) {
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
