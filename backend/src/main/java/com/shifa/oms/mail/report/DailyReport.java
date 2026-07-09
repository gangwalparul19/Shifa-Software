package com.shifa.oms.mail.report;

import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The consolidated daily report model — a pure projection of one day's orders
 * into the metrics the admin report email presents (Consolidated Daily Report
 * feature).
 *
 * <p>The model is built by the side-effect-free {@link #build(LocalDate, List)}
 * function, mirroring the existing pure style of
 * {@link com.shifa.oms.mail.template.EmailModels.Digest#from} and
 * {@link com.shifa.oms.mail.DailyDigestJob#buildDigestBody}. This lets the whole
 * aggregation be unit-tested deterministically without a database, the
 * scheduler, or Mockito.
 *
 * <p><strong>Revenue exclusions.</strong> Sales counts and money totals exclude
 * {@link OrderStatus#REJECTED} and {@link OrderStatus#CANCELLED} orders, which
 * never produced revenue (matching the digest / P&amp;L definition). The
 * order-status breakdown, however, counts <em>every</em> order regardless of
 * status, and the distinct-customer count considers every order that carries a
 * (non-blank) mobile.
 *
 * @param day                    the day summarised
 * @param overall                the business-wide totals
 * @param salespersons           per-salesperson rows, sorted by sales value desc
 * @param statusBreakdown        count per order status (only statuses present that day)
 * @param distinctCustomerCount  distinct customers (by mobile) with an order that day
 * @param topCustomer            the highest-value customer that day, or {@code null} if none
 */
public record DailyReport(
        LocalDate day,
        Overall overall,
        List<SalespersonRow> salespersons,
        List<StatusCount> statusBreakdown,
        int distinctCustomerCount,
        CustomerValue topCustomer) {

    /**
     * The business-wide summary block.
     *
     * @param orderCount              qualifying (sale) orders — excludes rejected/cancelled
     * @param totalSales              SUM of {@code totalAmount} over qualifying orders
     * @param codAmount               SUM of {@code codAmount} over qualifying orders
     * @param prepaidAmountReceived   SUM of {@code amountReceived} over qualifying orders
     * @param deliveredCount          orders in {@link OrderStatus#DELIVERED} that day
     * @param cancelledRejectedCount  orders in {@link OrderStatus#CANCELLED}/{@link OrderStatus#REJECTED}
     */
    public record Overall(
            int orderCount,
            BigDecimal totalSales,
            BigDecimal codAmount,
            BigDecimal prepaidAmountReceived,
            int deliveredCount,
            int cancelledRejectedCount) {
    }

    /**
     * A per-salesperson breakdown row (qualifying orders only).
     *
     * @param salespersonId   the creating user's id ({@code null} when unattributed)
     * @param salespersonName the resolved full name / username (fallback label when unresolved)
     * @param orderCount      qualifying orders created by this salesperson
     * @param salesValue      SUM of {@code totalAmount} over those orders
     */
    public record SalespersonRow(
            Long salespersonId,
            String salespersonName,
            int orderCount,
            BigDecimal salesValue) {
    }

    /**
     * A single order-status count.
     *
     * @param status the order status
     * @param count  how many of the day's orders are in this status
     */
    public record StatusCount(OrderStatus status, int count) {
    }

    /**
     * A customer and their qualifying sales value for the day.
     *
     * @param customerName   the customer's name (may be blank)
     * @param customerMobile the customer's mobile (the grouping key)
     * @param value          SUM of {@code totalAmount} over their qualifying orders
     */
    public record CustomerValue(String customerName, String customerMobile, BigDecimal value) {
    }

    /** Label shown for orders with no resolvable salesperson. */
    public static final String UNATTRIBUTED = "Unattributed";

    /**
     * Builds the consolidated report for {@code day} from the supplied per-order
     * projections — a pure function over plain data.
     *
     * @param day    the day being summarised (never {@code null})
     * @param orders the orders created on {@code day} (any statuses); may be empty
     * @return the fully-computed report model
     */
    public static DailyReport build(LocalDate day, List<ReportOrder> orders) {
        List<ReportOrder> safe = orders == null ? List.of() : orders;

        // ---- Overall + salesperson + customer aggregation over qualifying sales.
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal codAmount = BigDecimal.ZERO;
        BigDecimal prepaidReceived = BigDecimal.ZERO;
        int orderCount = 0;
        int deliveredCount = 0;
        int cancelledRejectedCount = 0;

        // Salesperson accumulators keyed by createdBy id (null → sentinel key).
        Map<Long, SalespersonAcc> bySalesperson = new LinkedHashMap<>();
        // Customer value accumulators keyed by mobile.
        Map<String, CustomerAcc> byCustomer = new LinkedHashMap<>();
        // Distinct customers (any status) by non-blank mobile.
        Set<String> distinctMobiles = new HashSet<>();
        // Status breakdown over ALL orders.
        Map<OrderStatus, Integer> statusCounts = new EnumMap<>(OrderStatus.class);

        for (ReportOrder o : safe) {
            if (o.status() != null) {
                statusCounts.merge(o.status(), 1, Integer::sum);
            }
            if (o.status() == OrderStatus.DELIVERED) {
                deliveredCount++;
            }
            if (o.status() == OrderStatus.CANCELLED || o.status() == OrderStatus.REJECTED) {
                cancelledRejectedCount++;
            }
            String mobile = o.customerMobile();
            if (mobile != null && !mobile.isBlank()) {
                distinctMobiles.add(mobile.trim());
            }

            if (!o.countsAsSale()) {
                continue;
            }
            orderCount++;
            BigDecimal amount = o.totalOrZero();
            totalSales = totalSales.add(amount);
            codAmount = codAmount.add(o.codOrZero());
            prepaidReceived = prepaidReceived.add(o.receivedOrZero());

            SalespersonAcc sp = bySalesperson.computeIfAbsent(o.createdBy(),
                    k -> new SalespersonAcc(o.createdBy(), o.salespersonName()));
            sp.add(amount, o.salespersonName());

            if (mobile != null && !mobile.isBlank()) {
                CustomerAcc c = byCustomer.computeIfAbsent(mobile.trim(),
                        k -> new CustomerAcc(mobile.trim(), o.customerName()));
                c.add(amount, o.customerName());
            }
        }

        Overall overall = new Overall(orderCount, totalSales, codAmount, prepaidReceived,
                deliveredCount, cancelledRejectedCount);

        // ---- Salesperson rows, sorted by sales value desc (tie-break: name, id).
        List<SalespersonRow> rows = new ArrayList<>();
        for (SalespersonAcc acc : bySalesperson.values()) {
            rows.add(new SalespersonRow(acc.id, acc.displayName(), acc.count, acc.value));
        }
        rows.sort(Comparator
                .comparing(SalespersonRow::salesValue, Comparator.reverseOrder())
                .thenComparing(r -> r.salespersonName() == null ? "" : r.salespersonName())
                .thenComparing(r -> r.salespersonId() == null ? Long.MIN_VALUE : r.salespersonId()));

        // ---- Status breakdown, in lifecycle (enum) order, only present statuses.
        List<StatusCount> breakdown = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            Integer count = statusCounts.get(status);
            if (count != null && count > 0) {
                breakdown.add(new StatusCount(status, count));
            }
        }

        // ---- Top customer by qualifying sales value.
        CustomerValue top = null;
        for (CustomerAcc c : byCustomer.values()) {
            if (top == null || c.value.compareTo(top.value()) > 0) {
                top = new CustomerValue(c.displayName(), c.mobile, c.value);
            }
        }

        return new DailyReport(day, overall, rows, breakdown, distinctMobiles.size(), top);
    }

    /** Mutable accumulator for per-salesperson aggregation (builder-internal). */
    private static final class SalespersonAcc {
        private final Long id;
        private String name;
        private int count;
        private BigDecimal value = BigDecimal.ZERO;

        SalespersonAcc(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        void add(BigDecimal amount, String candidateName) {
            this.count++;
            this.value = this.value.add(amount);
            if ((this.name == null || this.name.isBlank()) && candidateName != null && !candidateName.isBlank()) {
                this.name = candidateName;
            }
        }

        String displayName() {
            return (name == null || name.isBlank()) ? UNATTRIBUTED : name;
        }
    }

    /** Mutable accumulator for per-customer aggregation (builder-internal). */
    private static final class CustomerAcc {
        private final String mobile;
        private String name;
        private BigDecimal value = BigDecimal.ZERO;

        CustomerAcc(String mobile, String name) {
            this.mobile = mobile;
            this.name = name;
        }

        void add(BigDecimal amount, String candidateName) {
            this.value = this.value.add(amount);
            if ((this.name == null || this.name.isBlank()) && candidateName != null && !candidateName.isBlank()) {
                this.name = candidateName;
            }
        }

        String displayName() {
            return name == null ? "" : name;
        }
    }
}
