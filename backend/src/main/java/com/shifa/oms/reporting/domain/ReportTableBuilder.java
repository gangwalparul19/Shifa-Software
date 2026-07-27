package com.shifa.oms.reporting.domain;

import com.shifa.oms.reporting.domain.ReportRows.CountRow;
import com.shifa.oms.reporting.domain.ReportRows.DailyRow;
import com.shifa.oms.reporting.domain.ReportRows.DeliveryOutcome;
import com.shifa.oms.reporting.domain.ReportRows.MonthlyRow;
import com.shifa.oms.reporting.domain.ReportRows.ProductRow;
import com.shifa.oms.reporting.domain.ReportRows.StateRow;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@link ReportAggregator}'s outputs into the canonical
 * {@link TabularData} that the UI displays and every exporter renders. Keeping a
 * single place that defines each report's headers and cell order is what makes
 * "the export contains the same rows/columns as the displayed report" (Property
 * 24) hold by construction: the Excel and PDF exporters render this exact table.
 */
public class ReportTableBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * The required columns of the salesperson-wise report, in display order
     * (Req 20.3): customer name, mobile, product(s)+quantity, Total_Amount,
     * Amount_Received, COD_Amount, Payment_Status, Order_Status, COD settlement
     * status, loss claim status, AWB, order date.
     */
    public static final List<String> SALESPERSON_HEADERS = List.of(
            "Customer Name",
            "Mobile",
            "Products",
            "Total Amount",
            "Amount Received",
            "COD Amount",
            "Payment Status",
            "Order Status",
            "COD Settlement Status",
            "Loss Claim Status",
            "AWB",
            "Order Date");

    private final ReportAggregator aggregator;

    public ReportTableBuilder() {
        this(new ReportAggregator());
    }

    public ReportTableBuilder(ReportAggregator aggregator) {
        this.aggregator = aggregator;
    }

    /** Builds the displayed table for the given report type over the window. */
    public TabularData build(ReportType type, List<OrderReportRecord> orders, DateRange window) {
        return switch (type) {
            case DAILY -> daily(orders, window);
            case MONTHLY -> monthly(orders, window);
            case PRODUCT -> product(orders, window);
            case STATE -> state(orders, window);
            case CUSTOMER -> customer(orders, window);
            case SALESPERSON -> salesperson(orders, window);
            case ORDERS_BY_LEAD_SOURCE -> countTable(
                    "Lead Source", aggregator.ordersByLeadSource(orders, window));
            case ORDERS_BY_STATUS -> countTable(
                    "Status", aggregator.ordersByStatus(orders, window));
            case ORDERS_BY_SALESPERSON -> countTable(
                    "Salesperson", aggregator.ordersBySalesperson(orders, window));
            case DELIVERY_OUTCOME -> deliveryOutcome(orders, window);
            case PAYMENTS -> payments(orders, window);
            case OUTSTANDING -> outstanding(orders, window);
            case COD_REMITTANCE -> codRemittance(orders, window);
            // Per-module reports (expenses/procurement/returns/inventory) are built by
            // ModuleReportService and routed there by ReportService before reaching here.
            default -> throw new IllegalArgumentException(
                    "Not an order-based report type: " + type);
        };
    }

    // --- Money / receivables (accountant) -----------------------------------

    /** Order statuses whose money is written off (never collectible) — excluded from dues. */
    private static boolean isCancelledOrRejected(OrderReportRecord o) {
        return o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.CANCELLED
                || o.orderStatus() == com.shifa.oms.statemachine.OrderStatus.REJECTED;
    }

    /**
     * Daily money view: per order-date within the window, the order count, total
     * sales, amount received, COD amount, and outstanding (total − received).
     */
    private TabularData payments(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of(
                "Date", "Orders", "Total Sales", "Amount Received", "COD Amount", "Outstanding");
        // Ordered by date so the daily cash trend reads top-to-bottom.
        java.util.TreeMap<java.time.LocalDate, BigDecimal[]> byDate = new java.util.TreeMap<>();
        java.util.TreeMap<java.time.LocalDate, long[]> counts = new java.util.TreeMap<>();
        for (OrderReportRecord o : orders) {
            if (o.orderDate() == null || !window.contains(o.orderDate()) || isCancelledOrRejected(o)) {
                continue;
            }
            BigDecimal[] acc = byDate.computeIfAbsent(o.orderDate(),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            acc[0] = acc[0].add(o.totalAmount());
            acc[1] = acc[1].add(o.amountReceived());
            acc[2] = acc[2].add(o.codAmount());
            acc[3] = acc[3].add(o.totalAmount().subtract(o.amountReceived()));
            counts.computeIfAbsent(o.orderDate(), k -> new long[1])[0]++;
        }
        List<List<String>> rows = new ArrayList<>();
        for (var e : byDate.entrySet()) {
            BigDecimal[] a = e.getValue();
            rows.add(List.of(
                    e.getKey().format(DATE),
                    Long.toString(counts.get(e.getKey())[0]),
                    money(a[0]), money(a[1]), money(a[2]), money(a[3])));
        }
        return new TabularData(headers, rows);
    }

    /**
     * Per-order outstanding balances still collectible (total − received &gt; 0,
     * excluding cancelled/rejected), oldest first with days outstanding — the
     * accountant's chase list for money still to come in.
     */
    private TabularData outstanding(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of(
                "Order", "Customer", "Mobile", "Order Date", "Days", "Total",
                "Received", "Balance Due", "Order Status", "COD Status");
        java.time.LocalDate reference = referenceDate(orders, window);
        List<OrderReportRecord> due = new ArrayList<>();
        for (OrderReportRecord o : orders) {
            if (o.orderDate() == null || !window.contains(o.orderDate()) || isCancelledOrRejected(o)) {
                continue;
            }
            if (o.totalAmount().subtract(o.amountReceived()).signum() > 0) {
                due.add(o);
            }
        }
        // Oldest dues first (largest days outstanding), so the accountant chases them first.
        due.sort(java.util.Comparator.comparing(OrderReportRecord::orderDate));
        List<List<String>> rows = new ArrayList<>();
        for (OrderReportRecord o : due) {
            BigDecimal balance = o.totalAmount().subtract(o.amountReceived());
            rows.add(List.of(
                    nullToEmpty(o.orderCode()),
                    nullToEmpty(o.customerName()),
                    nullToEmpty(o.customerMobile()),
                    o.orderDate().format(DATE),
                    daysBetween(o.orderDate(), reference),
                    money(o.totalAmount()),
                    money(o.amountReceived()),
                    money(balance),
                    o.orderStatus() == null ? "" : o.orderStatus().name(),
                    nullToEmpty(o.codSettlementStatus())));
        }
        return new TabularData(headers, rows);
    }

    /**
     * COD amounts pending remittance from the delivery partner (COD settlement
     * status "Pending"), oldest first with days outstanding — what to chase the
     * courier for.
     */
    private TabularData codRemittance(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of(
                "Order", "Customer", "AWB", "Order Date", "Days", "COD Amount", "Order Status");
        java.time.LocalDate reference = referenceDate(orders, window);
        List<OrderReportRecord> pending = new ArrayList<>();
        for (OrderReportRecord o : orders) {
            if (o.orderDate() == null || !window.contains(o.orderDate())) {
                continue;
            }
            // "Pending" = a COD receivable exists and is not yet settled by the courier.
            if ("Pending".equalsIgnoreCase(o.codSettlementStatus())) {
                pending.add(o);
            }
        }
        pending.sort(java.util.Comparator.comparing(OrderReportRecord::orderDate));
        List<List<String>> rows = new ArrayList<>();
        for (OrderReportRecord o : pending) {
            rows.add(List.of(
                    nullToEmpty(o.orderCode()),
                    nullToEmpty(o.customerName()),
                    nullToEmpty(o.awb()),
                    o.orderDate().format(DATE),
                    daysBetween(o.orderDate(), reference),
                    money(o.codAmount()),
                    o.orderStatus() == null ? "" : o.orderStatus().name()));
        }
        return new TabularData(headers, rows);
    }

    /**
     * The reference date for "days outstanding": the window's upper bound when
     * set, else the latest order date in the set (deterministic + pure — no clock).
     */
    private static java.time.LocalDate referenceDate(List<OrderReportRecord> orders, DateRange window) {
        if (window.to() != null) {
            return window.to();
        }
        java.time.LocalDate max = null;
        for (OrderReportRecord o : orders) {
            if (o.orderDate() != null && (max == null || o.orderDate().isAfter(max))) {
                max = o.orderDate();
            }
        }
        return max;
    }

    /** Whole days from {@code date} to {@code reference}, or "" when unknown. */
    private static String daysBetween(java.time.LocalDate date, java.time.LocalDate reference) {
        if (date == null || reference == null) {
            return "";
        }
        return Long.toString(java.time.temporal.ChronoUnit.DAYS.between(date, reference));
    }

    private TabularData daily(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of("Date", "Orders", "Total Sales");
        List<List<String>> rows = new ArrayList<>();
        for (DailyRow r : aggregator.daily(orders, window)) {
            rows.add(List.of(r.date().format(DATE), Long.toString(r.orderCount()), money(r.totalSales())));
        }
        return new TabularData(headers, rows);
    }

    private TabularData monthly(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of("Month", "Orders", "Total Sales");
        List<List<String>> rows = new ArrayList<>();
        for (MonthlyRow r : aggregator.monthly(orders, window)) {
            rows.add(List.of(r.month().toString(), Long.toString(r.orderCount()), money(r.totalSales())));
        }
        return new TabularData(headers, rows);
    }

    private TabularData product(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of("Product", "Quantity", "Total Sales");
        List<List<String>> rows = new ArrayList<>();
        for (ProductRow r : aggregator.productWise(orders, window)) {
            rows.add(List.of(r.productName(), Long.toString(r.quantity()), money(r.totalSales())));
        }
        return new TabularData(headers, rows);
    }

    private TabularData state(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of("State", "Orders", "Total Sales");
        List<List<String>> rows = new ArrayList<>();
        for (StateRow r : aggregator.stateWise(orders, window)) {
            rows.add(List.of(r.state(), Long.toString(r.orderCount()), money(r.totalSales())));
        }
        return new TabularData(headers, rows);
    }

    private TabularData customer(List<OrderReportRecord> orders, DateRange window) {
        List<String> headers = List.of("Customer", "Mobile", "Orders", "Total Sales");
        List<List<String>> rows = new ArrayList<>();
        for (ReportRows.CustomerRow r : aggregator.customerWise(orders, window)) {
            rows.add(List.of(
                    nullToEmpty(r.customerName()),
                    nullToEmpty(r.customerMobile()),
                    Long.toString(r.orderCount()),
                    money(r.totalSales())));
        }
        return new TabularData(headers, rows);
    }

    private TabularData salesperson(List<OrderReportRecord> orders, DateRange window) {
        List<List<String>> rows = new ArrayList<>();
        for (OrderReportRecord o : aggregator.salespersonWise(orders, window)) {
            rows.add(salespersonRow(o));
        }
        return new TabularData(SALESPERSON_HEADERS, rows);
    }

    /** A generic two-column grouped-count table ({@code keyLabel}, "Orders"). */
    private TabularData countTable(String keyLabel, List<CountRow> rows) {
        List<String> headers = List.of(keyLabel, "Orders");
        List<List<String>> cells = new ArrayList<>();
        for (CountRow r : rows) {
            cells.add(List.of(r.key(), Long.toString(r.orderCount())));
        }
        return new TabularData(headers, cells);
    }

    /**
     * The delivery-outcome table (Req 16.4): one row per outcome count plus a
     * final delivery success-rate row (percentage).
     */
    private TabularData deliveryOutcome(List<OrderReportRecord> orders, DateRange window) {
        DeliveryOutcome outcome = aggregator.deliveryOutcome(orders, window);
        List<String> headers = List.of("Metric", "Value");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Delivered", Long.toString(outcome.delivered())));
        rows.add(List.of("Customer Rejected", Long.toString(outcome.customerRejected())));
        rows.add(List.of("Delivery Failed", Long.toString(outcome.deliveryFailed())));
        rows.add(List.of("Cancelled", Long.toString(outcome.cancelled())));
        rows.add(List.of("Delivery Success Rate (%)", money(outcome.successRate())));
        return new TabularData(headers, rows);
    }

    /** The cells of a single salesperson-wise row, aligned with {@link #SALESPERSON_HEADERS}. */
    public List<String> salespersonRow(OrderReportRecord o) {
        List<String> cells = new ArrayList<>();
        cells.add(nullToEmpty(o.customerName()));
        cells.add(nullToEmpty(o.customerMobile()));
        cells.add(o.productSummary());
        cells.add(money(o.totalAmount()));
        cells.add(money(o.amountReceived()));
        cells.add(money(o.codAmount()));
        cells.add(o.paymentStatus() == null ? "" : o.paymentStatus().name());
        cells.add(o.orderStatus() == null ? "" : o.orderStatus().name());
        cells.add(nullToEmpty(o.codSettlementStatus()));
        cells.add(nullToEmpty(o.claimStatus()));
        cells.add(nullToEmpty(o.awb()));
        cells.add(o.orderDate() == null ? "" : o.orderDate().format(DATE));
        return cells;
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
