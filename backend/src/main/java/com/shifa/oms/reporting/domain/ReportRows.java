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

    /**
     * One row of the weekly report: the ISO week (its Monday start date and the
     * {@code yyyy-Www} label), the order count, and total sales for that week.
     * ISO-8601 weeks start on Monday; a week is attributed to the Monday of the
     * week containing the order date, so the report reads as consecutive weeks.
     */
    public record WeeklyRow(LocalDate weekStart, String weekLabel, long orderCount, BigDecimal totalSales) {
    }

    /**
     * One row of the per-salesperson summary report: the salesperson id, their
     * order count and total sales over the window, amount actually received,
     * average order value, delivered-order count, delivery success rate (%), and
     * the COD amount still pending remittance from the courier. This is the
     * owner's "which salesperson is performing, and by how much money" view —
     * one row per salesperson (ids are resolved to names by the service).
     */
    public record SalespersonSummaryRow(
            Long salespersonId,
            long orderCount,
            BigDecimal totalSales,
            BigDecimal amountReceived,
            BigDecimal avgOrderValue,
            long deliveredCount,
            BigDecimal deliverySuccessRate,
            BigDecimal codPending) {
    }

    /**
     * One row of a grouped-count report that ALSO carries the revenue for that
     * group: a key label, the order count, and the total sales. Used by the
     * orders-by-salesperson report so it shows amount alongside the count
     * (the plain {@link CountRow} grouping is kept for the count-only reports).
     */
    public record CountSalesRow(String key, long orderCount, BigDecimal totalSales) {
    }

    /**
     * One row of the payment-method report: a payment status (Pay on Delivery /
     * Partially Paid / Fully Paid), the order count, the total sales, and the
     * amount actually received for orders of that payment type — the owner's
     * "how are customers paying, and how much has actually come in" view.
     */
    public record PaymentMethodRow(String paymentStatus, long orderCount,
                                   BigDecimal totalSales, BigDecimal amountReceived) {
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
     * The delivery-outcome summary over a range (Req 16.4): the counts of the
     * four terminal outcomes (kept separate) plus the delivery success rate,
     * expressed as a percentage {@code DELIVERED / (DELIVERED + CUSTOMER_REJECTED
     * + DELIVERY_FAILED + CANCELLED) * 100} (0 when the denominator is 0).
     */
    public record DeliveryOutcome(long delivered, long customerRejected, long deliveryFailed,
                                  long cancelled, BigDecimal successRate) {
    }
}
