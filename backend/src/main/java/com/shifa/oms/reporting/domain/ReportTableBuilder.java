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
        };
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
