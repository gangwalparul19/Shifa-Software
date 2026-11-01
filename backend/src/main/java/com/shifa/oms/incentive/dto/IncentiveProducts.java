package com.shifa.oms.incentive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Day-by-day product sales per salesperson. */
public final class IncentiveProducts {

    private IncentiveProducts() {
    }

    /**
     * @param personId  the person asked for, or null for everyone
     * @param rows      one row per day, salesperson and product
     * @param days      one row per day and salesperson, tying the product lines back to the day's sales
     * @param products  the same products added up over the whole period
     */
    public record Response(LocalDate from, LocalDate to, Long personId, List<ProductRow> rows,
                           List<DayReconciliation> days, List<ProductTotal> products, Totals totals) {
    }

    /**
     * Units and sales of one product sold by one person on one day. {@code sales} is the sum of the
     * product's order lines (price x quantity), before any order-level discount.
     */
    public record ProductRow(LocalDate date, long personId, String personName, String productName,
                             long quantity, BigDecimal sales, long orders) {
    }

    /**
     * A person's day: the orders' total against the sum of their product lines. They differ by the
     * order-level discount and rupee round-off, shown as {@code adjustment} (day total minus lines).
     */
    public record DayReconciliation(LocalDate date, long personId, String personName, long orders,
                                    BigDecimal dayTotal, BigDecimal lineTotal, BigDecimal adjustment) {
    }

    /** One product over the whole period. */
    public record ProductTotal(String productName, long quantity, BigDecimal sales) {
    }

    /** Totals of the period. {@code dayTotal} is the net sales (what the tracker and bonus use). */
    public record Totals(long orders, long quantity, BigDecimal lineTotal, BigDecimal dayTotal) {
    }
}
