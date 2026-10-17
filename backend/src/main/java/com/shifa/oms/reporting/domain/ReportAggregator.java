package com.shifa.oms.reporting.domain;

import com.shifa.oms.order.LeadSource;
import com.shifa.oms.reporting.domain.ReportRows.DailyRow;
import com.shifa.oms.reporting.domain.ReportRows.DeliveryOutcome;
import com.shifa.oms.reporting.domain.ReportRows.MonthlyRow;
import com.shifa.oms.reporting.domain.ReportRows.ProductRow;
import com.shifa.oms.reporting.domain.ReportRows.StateRow;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure report/metrics aggregation over a set of {@link OrderReportRecord}s,
 * restricted to a {@link DateRange} window (Req 20.1, 20.2, 20.3; and the
 * metrics aggregation of Req 19.3, 19.4, 19.7).
 *
 * <p>This class holds no persistence or web concerns: it takes plain order
 * projections and a window and computes the daily / monthly / product-wise /
 * state-wise / salesperson-wise reports, previous-period percentage change, and
 * the top performers (argmax within the window). Keeping it pure is what lets
 * Property 23 exercise it directly over generated inputs.
 *
 * <p>The core invariant every method upholds: an order participates in a report
 * <em>iff</em> its {@link OrderReportRecord#orderDate()} is within the window
 * (Req 20.2).
 */
public class ReportAggregator {

    /**
     * Order statuses that never produced sellable revenue and MUST be excluded
     * from every sales / turnover figure (matches the P&amp;L and performance
     * definition): an admin-rejected, payment-rejected, or cancelled order is not
     * a sale. Grouped-count / status-breakdown reports keep counting them (they
     * exist to show them) — only the money/sales aggregations drop them.
     */
    private static final java.util.Set<OrderStatus> NON_REVENUE = java.util.EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** The orders whose order date falls within the window (Req 20.2). */
    public List<OrderReportRecord> within(List<OrderReportRecord> orders, DateRange window) {
        List<OrderReportRecord> result = new ArrayList<>();
        for (OrderReportRecord o : orders) {
            if (window.contains(o.orderDate())) {
                result.add(o);
            }
        }
        return result;
    }

    /**
     * Windowed orders that count as revenue — same as {@link #within} but with the
     * {@link #NON_REVENUE} statuses (rejected / payment-rejected / cancelled)
     * removed. Used by every sales/turnover aggregation so those orders never
     * inflate a salesperson's total or the company turnover.
     */
    public List<OrderReportRecord> revenueWithin(List<OrderReportRecord> orders, DateRange window) {
        List<OrderReportRecord> result = new ArrayList<>();
        for (OrderReportRecord o : within(orders, window)) {
            if (!NON_REVENUE.contains(o.orderStatus())) {
                result.add(o);
            }
        }
        return result;
    }

    // --- Grouped reports ----------------------------------------------------

    /** Daily report: one row per order date within the window, ascending (Req 20.1). */
    public List<DailyRow> daily(List<OrderReportRecord> orders, DateRange window) {
        Map<LocalDate, long[]> counts = new TreeMap<>();
        Map<LocalDate, BigDecimal> sales = new TreeMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            counts.computeIfAbsent(o.orderDate(), k -> new long[1])[0]++;
            sales.merge(o.orderDate(), o.totalAmount(), BigDecimal::add);
        }
        List<DailyRow> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : counts.entrySet()) {
            rows.add(new DailyRow(e.getKey(), e.getValue()[0], scale(sales.get(e.getKey()))));
        }
        return rows;
    }

    /** Monthly report: one row per calendar month within the window, ascending (Req 20.1). */
    public List<MonthlyRow> monthly(List<OrderReportRecord> orders, DateRange window) {
        Map<YearMonth, long[]> counts = new TreeMap<>();
        Map<YearMonth, BigDecimal> sales = new TreeMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            YearMonth ym = YearMonth.from(o.orderDate());
            counts.computeIfAbsent(ym, k -> new long[1])[0]++;
            sales.merge(ym, o.totalAmount(), BigDecimal::add);
        }
        List<MonthlyRow> rows = new ArrayList<>();
        for (Map.Entry<YearMonth, long[]> e : counts.entrySet()) {
            rows.add(new MonthlyRow(e.getKey(), e.getValue()[0], scale(sales.get(e.getKey()))));
        }
        return rows;
    }

    /**
     * Weekly report: one row per ISO-8601 week within the window, ascending. Each
     * order is attributed to the Monday of the week containing its order date, so
     * the rows read as consecutive weeks with a {@code yyyy-Www} label. Excludes
     * the non-revenue statuses, like the daily/monthly views.
     */
    public List<ReportRows.WeeklyRow> weekly(List<OrderReportRecord> orders, DateRange window) {
        Map<LocalDate, long[]> counts = new TreeMap<>();
        Map<LocalDate, BigDecimal> sales = new TreeMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            LocalDate weekStart = weekStart(o.orderDate());
            counts.computeIfAbsent(weekStart, k -> new long[1])[0]++;
            sales.merge(weekStart, o.totalAmount(), BigDecimal::add);
        }
        List<ReportRows.WeeklyRow> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : counts.entrySet()) {
            rows.add(new ReportRows.WeeklyRow(
                    e.getKey(), weekLabel(e.getKey()), e.getValue()[0], scale(sales.get(e.getKey()))));
        }
        return rows;
    }

    /** The Monday of the ISO week containing {@code date}. */
    private static LocalDate weekStart(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    /** The {@code yyyy-Www} ISO week label for the week starting on {@code weekStart}. */
    private static String weekLabel(LocalDate weekStart) {
        java.time.temporal.WeekFields iso = java.time.temporal.WeekFields.ISO;
        int weekOfYear = weekStart.get(iso.weekOfWeekBasedYear());
        int weekYear = weekStart.get(iso.weekBasedYear());
        return String.format("%d-W%02d", weekYear, weekOfYear);
    }

    /**
     * Per-salesperson summary: one row per salesperson over the window carrying
     * their order count, total sales, amount received, average order value,
     * delivered count, delivery success rate, and pending COD — the "which
     * salesperson is performing, by money" view the owner asked for.
     *
     * <p>Sales/received/AOV/COD are computed over revenue orders (excludes
     * rejected / payment-rejected / cancelled, matching the P&amp;L definition);
     * the delivered count and success rate are computed over ALL windowed orders
     * for that salesperson (so cancellations/failures correctly drag the rate
     * down). Null-salesperson orders are bucketed under {@code UNSPECIFIED}
     * ({@code salespersonId == null}). Ordered by total sales descending, then id.
     */
    public List<ReportRows.SalespersonSummaryRow> salespersonSummary(
            List<OrderReportRecord> orders, DateRange window) {
        // Revenue-side accumulators (orders, sales, received, cod-pending), keyed by id.
        Map<Long, long[]> orderCounts = new LinkedHashMap<>();
        Map<Long, BigDecimal> sales = new LinkedHashMap<>();
        Map<Long, BigDecimal> received = new LinkedHashMap<>();
        Map<Long, BigDecimal> codPending = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            Long key = o.salespersonId();
            orderCounts.computeIfAbsent(key, k -> new long[1])[0]++;
            sales.merge(key, o.totalAmount(), BigDecimal::add);
            received.merge(key, o.amountReceived(), BigDecimal::add);
            if ("Pending".equalsIgnoreCase(o.codSettlementStatus())) {
                codPending.merge(key, o.codAmount(), BigDecimal::add);
            }
        }
        // Delivery outcome over ALL windowed orders (so failures drag the rate down).
        Map<Long, long[]> delivered = new LinkedHashMap<>();
        Map<Long, long[]> outcomeDenominator = new LinkedHashMap<>();
        for (OrderReportRecord o : within(orders, window)) {
            Long key = o.salespersonId();
            OrderStatus s = o.orderStatus();
            if (s == OrderStatus.DELIVERED) {
                delivered.computeIfAbsent(key, k -> new long[1])[0]++;
                outcomeDenominator.computeIfAbsent(key, k -> new long[1])[0]++;
            } else if (s == OrderStatus.CUSTOMER_REJECTED || s == OrderStatus.DELIVERY_FAILED
                    || s == OrderStatus.CANCELLED) {
                outcomeDenominator.computeIfAbsent(key, k -> new long[1])[0]++;
            }
        }
        List<ReportRows.SalespersonSummaryRow> rows = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : orderCounts.entrySet()) {
            Long key = e.getKey();
            long count = e.getValue()[0];
            BigDecimal totalSales = scale(sales.getOrDefault(key, BigDecimal.ZERO));
            BigDecimal rec = scale(received.getOrDefault(key, BigDecimal.ZERO));
            BigDecimal aov = count == 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : totalSales.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            long deliveredCount = delivered.getOrDefault(key, new long[1])[0];
            long denom = outcomeDenominator.getOrDefault(key, new long[1])[0];
            BigDecimal successRate = denom == 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(deliveredCount)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
            rows.add(new ReportRows.SalespersonSummaryRow(
                    key, count, totalSales, rec, aov, deliveredCount, successRate,
                    scale(codPending.getOrDefault(key, BigDecimal.ZERO))));
        }
        rows.sort(Comparator.comparing(ReportRows.SalespersonSummaryRow::totalSales).reversed()
                .thenComparing(r -> r.salespersonId() == null ? Long.MAX_VALUE : r.salespersonId()));
        return rows;
    }

    /**
     * Product-wise report: one row per product within the window, summing the
     * quantity sold and the sales it contributed (rate × quantity per line),
     * ordered by quantity descending then name (Req 20.1).
     */
    public List<ProductRow> productWise(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> qty = new LinkedHashMap<>();
        Map<String, BigDecimal> sales = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            for (OrderReportRecord.ProductLine line : o.products()) {
                qty.computeIfAbsent(line.productName(), k -> new long[1])[0] += line.quantity();
                sales.merge(line.productName(), line.lineTotal(), BigDecimal::add);
            }
        }
        List<ProductRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : qty.entrySet()) {
            rows.add(new ProductRow(e.getKey(), e.getValue()[0],
                    scale(sales.getOrDefault(e.getKey(), BigDecimal.ZERO))));
        }
        rows.sort(Comparator.comparingLong(ProductRow::quantity).reversed()
                .thenComparing(ProductRow::productName));
        return rows;
    }

    /**
     * State-wise report: one row per destination state within the window, with
     * order count and total sales, ordered by sales descending then state name
     * (Req 20.1).
     */
    public List<StateRow> stateWise(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> sales = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            String state = o.state() == null ? "" : o.state();
            counts.computeIfAbsent(state, k -> new long[1])[0]++;
            sales.merge(state, o.totalAmount(), BigDecimal::add);
        }
        List<StateRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            rows.add(new StateRow(e.getKey(), e.getValue()[0], scale(sales.get(e.getKey()))));
        }
        rows.sort(Comparator.comparing(StateRow::totalSales).reversed()
                .thenComparing(StateRow::state));
        return rows;
    }

    /**
     * Customer-wise report: one row per customer (keyed by mobile, falling back
     * to name when the mobile is blank) within the window, with their order
     * count and total sales, ordered by sales descending then customer name.
     */
    public List<ReportRows.CustomerRow> customerWise(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> sales = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> mobiles = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            String mobile = o.customerMobile() == null ? "" : o.customerMobile();
            String name = o.customerName() == null ? "" : o.customerName();
            String key = !mobile.isBlank() ? mobile : name;
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            sales.merge(key, o.totalAmount(), BigDecimal::add);
            names.putIfAbsent(key, name);
            mobiles.putIfAbsent(key, mobile);
        }
        List<ReportRows.CustomerRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            String key = e.getKey();
            rows.add(new ReportRows.CustomerRow(
                    names.getOrDefault(key, ""),
                    mobiles.getOrDefault(key, ""),
                    e.getValue()[0],
                    scale(sales.get(key))));
        }
        rows.sort(Comparator.comparing(ReportRows.CustomerRow::totalSales).reversed()
                .thenComparing(ReportRows.CustomerRow::customerName));
        return rows;
    }

    /**
     * Salesperson-wise detailed report: the windowed orders themselves, ordered
     * by order date descending then id, each carrying every required column
     * (Req 20.3). Scoping to a single salesperson is applied upstream by the
     * service; this method simply windows and orders.
     */
    public List<OrderReportRecord> salespersonWise(List<OrderReportRecord> orders, DateRange window) {
        List<OrderReportRecord> rows = new ArrayList<>(within(orders, window));
        rows.sort(Comparator.comparing(OrderReportRecord::orderDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                .thenComparing(o -> o.orderId() == null ? 0L : o.orderId()));
        return rows;
    }

    // --- Grouped-count reports (Req 16.1, 16.2, 16.3, 16.4) -----------------

    /** The label a {@code null} lead source is reported under (Req 16.1). */
    public static final String UNSPECIFIED_LEAD_SOURCE = "UNSPECIFIED";

    /** The label a {@code null} salesperson ({@code createdBy}) is reported under. */
    public static final String UNSPECIFIED_SALESPERSON = "UNSPECIFIED";

    /**
     * Orders grouped by {@link LeadSource} over the window, keyed by the source
     * name with a {@code null} lead source bucketed as {@code UNSPECIFIED}
     * (Req 16.1). Keys are ordered by descending count then key name.
     */
    public List<ReportRows.CountRow> ordersByLeadSource(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (OrderReportRecord o : within(orders, window)) {
            String key = o.leadSource() == null ? UNSPECIFIED_LEAD_SOURCE : o.leadSource().name();
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
        }
        return toSortedCountRows(counts);
    }

    /**
     * Orders grouped by {@link OrderStatus} over the window (Req 16.2). Keys are
     * ordered by descending count then status name.
     */
    public List<ReportRows.CountRow> ordersByStatus(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (OrderReportRecord o : within(orders, window)) {
            String key = o.orderStatus() == null ? "" : o.orderStatus().name();
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
        }
        return toSortedCountRows(counts);
    }

    /**
     * Orders grouped by salesperson ({@code createdBy}) over the window
     * (Req 16.3), keyed by the salesperson id as a string with a {@code null}
     * creator bucketed as {@code UNSPECIFIED}. Ordered by descending count then key.
     */
    public List<ReportRows.CountRow> ordersBySalesperson(List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (OrderReportRecord o : within(orders, window)) {
            String key = o.salespersonId() == null
                    ? UNSPECIFIED_SALESPERSON : Long.toString(o.salespersonId());
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
        }
        return toSortedCountRows(counts);
    }

    /**
     * Orders grouped by salesperson ({@code createdBy}) over the window, carrying
     * BOTH the order count AND the total sales revenue for each — so the
     * orders-by-salesperson report can show amount alongside the count. The count
     * covers every windowed order (so it matches {@link #ordersBySalesperson});
     * the sales figure sums only revenue orders (excludes rejected /
     * payment-rejected / cancelled), consistent with every other money figure.
     * A {@code null} creator is bucketed as {@code UNSPECIFIED}. Ordered by sales
     * descending, then key.
     */
    public List<ReportRows.CountSalesRow> ordersBySalespersonWithSales(
            List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> sales = new LinkedHashMap<>();
        for (OrderReportRecord o : within(orders, window)) {
            String key = o.salespersonId() == null
                    ? UNSPECIFIED_SALESPERSON : Long.toString(o.salespersonId());
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            if (!NON_REVENUE.contains(o.orderStatus())) {
                sales.merge(key, o.totalAmount(), BigDecimal::add);
            }
        }
        List<ReportRows.CountSalesRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            rows.add(new ReportRows.CountSalesRow(
                    e.getKey(), e.getValue()[0],
                    scale(sales.getOrDefault(e.getKey(), BigDecimal.ZERO))));
        }
        rows.sort(Comparator.comparing(ReportRows.CountSalesRow::totalSales).reversed()
                .thenComparing(ReportRows.CountSalesRow::key));
        return rows;
    }

    /**
     * Orders grouped by payment status (Pay on Delivery / Partially Paid / Fully
     * Paid) over the window, with the order count, total sales, and amount
     * actually received for each. Computed over revenue orders (excludes
     * rejected / payment-rejected / cancelled), consistent with the other money
     * figures. Ordered by total sales descending, then status name. The payment
     * status is reported as the enum name; the display layer humanises it.
     */
    public List<ReportRows.PaymentMethodRow> ordersByPaymentMethod(
            List<OrderReportRecord> orders, DateRange window) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> sales = new LinkedHashMap<>();
        Map<String, BigDecimal> received = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            String key = o.paymentStatus() == null ? "UNSPECIFIED" : o.paymentStatus().name();
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            sales.merge(key, o.totalAmount(), BigDecimal::add);
            received.merge(key, o.amountReceived(), BigDecimal::add);
        }
        List<ReportRows.PaymentMethodRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            String key = e.getKey();
            rows.add(new ReportRows.PaymentMethodRow(
                    key, e.getValue()[0],
                    scale(sales.getOrDefault(key, BigDecimal.ZERO)),
                    scale(received.getOrDefault(key, BigDecimal.ZERO))));
        }
        rows.sort(Comparator.comparing(ReportRows.PaymentMethodRow::totalSales).reversed()
                .thenComparing(ReportRows.PaymentMethodRow::paymentStatus));
        return rows;
    }

    /**
     * The delivery-outcome summary over the window (Req 16.4): the counts of
     * {@code DELIVERED}, {@code CUSTOMER_REJECTED}, {@code DELIVERY_FAILED}, and
     * {@code CANCELLED} (kept separate), plus the delivery success rate as a
     * percentage {@code DELIVERED / (DELIVERED + CUSTOMER_REJECTED +
     * DELIVERY_FAILED + CANCELLED) * 100}, or {@code 0} when the denominator is 0.
     */
    public DeliveryOutcome deliveryOutcome(List<OrderReportRecord> orders, DateRange window) {
        long delivered = 0;
        long customerRejected = 0;
        long deliveryFailed = 0;
        long cancelled = 0;
        for (OrderReportRecord o : within(orders, window)) {
            OrderStatus s = o.orderStatus();
            if (s == OrderStatus.DELIVERED) {
                delivered++;
            } else if (s == OrderStatus.CUSTOMER_REJECTED) {
                customerRejected++;
            } else if (s == OrderStatus.DELIVERY_FAILED) {
                deliveryFailed++;
            } else if (s == OrderStatus.CANCELLED) {
                cancelled++;
            }
        }
        long denominator = delivered + customerRejected + deliveryFailed + cancelled;
        BigDecimal successRate = denominator == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(delivered)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        return new DeliveryOutcome(delivered, customerRejected, deliveryFailed, cancelled, successRate);
    }

    /** Orders a group-count map by descending count then key name into {@code CountRow}s. */
    private static List<ReportRows.CountRow> toSortedCountRows(Map<String, long[]> counts) {
        List<ReportRows.CountRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            rows.add(new ReportRows.CountRow(e.getKey(), e.getValue()[0]));
        }
        rows.sort(Comparator.comparingLong(ReportRows.CountRow::orderCount).reversed()
                .thenComparing(ReportRows.CountRow::key));
        return rows;
    }

    // --- Metrics (Req 19.3, 19.4, 19.7) -------------------------------------

    /** Total sales (sum of {@code totalAmount}) over the windowed orders (Req 19.3). */
    public BigDecimal totalSales(List<OrderReportRecord> orders, DateRange window) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            total = total.add(o.totalAmount());
        }
        return scale(total);
    }

    /** Number of revenue orders within the window (Req 19.3; excludes non-revenue). */
    public long orderCount(List<OrderReportRecord> orders, DateRange window) {
        return revenueWithin(orders, window).size();
    }

    /**
     * The previous-period percentage change of total sales: total sales in
     * {@code window} versus total sales in {@code window.previousPeriod()}
     * (Req 19.4). If the window is unbounded (no previous period), the change is
     * not applicable.
     */
    public PercentChange salesPercentChange(List<OrderReportRecord> orders, DateRange window) {
        DateRange previous = window.previousPeriod();
        if (previous == null) {
            return PercentChange.NOT_APPLICABLE;
        }
        return PercentChange.of(totalSales(orders, window), totalSales(orders, previous));
    }

    /** Top salesperson by total sales within the window (argmax), if any (Req 19.7). */
    public Optional<Long> topSalesperson(List<OrderReportRecord> orders, DateRange window) {
        Map<Long, BigDecimal> byPerson = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            if (o.salespersonId() == null) {
                continue;
            }
            byPerson.merge(o.salespersonId(), o.totalAmount(), BigDecimal::add);
        }
        return argmax(byPerson);
    }

    /** Top-selling product by quantity within the window (argmax), if any (Req 19.7). */
    public Optional<String> topProduct(List<OrderReportRecord> orders, DateRange window) {
        Map<String, BigDecimal> byProduct = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            for (OrderReportRecord.ProductLine line : o.products()) {
                byProduct.merge(line.productName(), BigDecimal.valueOf(line.quantity()), BigDecimal::add);
            }
        }
        return argmax(byProduct);
    }

    /** Top state by total sales within the window (argmax), if any (Req 19.7). */
    public Optional<String> topState(List<OrderReportRecord> orders, DateRange window) {
        Map<String, BigDecimal> byState = new LinkedHashMap<>();
        for (OrderReportRecord o : revenueWithin(orders, window)) {
            String state = o.state() == null ? "" : o.state();
            byState.merge(state, o.totalAmount(), BigDecimal::add);
        }
        return argmax(byState);
    }

    /**
     * The key with the greatest accumulated value; ties are broken by insertion
     * order (the first-seen key wins), giving a deterministic argmax.
     */
    private static <K> Optional<K> argmax(Map<K, BigDecimal> byKey) {
        K best = null;
        BigDecimal bestValue = null;
        for (Map.Entry<K, BigDecimal> e : byKey.entrySet()) {
            if (bestValue == null || e.getValue().compareTo(bestValue) > 0) {
                best = e.getKey();
                bestValue = e.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
