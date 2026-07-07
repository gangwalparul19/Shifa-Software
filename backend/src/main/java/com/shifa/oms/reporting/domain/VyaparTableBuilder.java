package com.shifa.oms.reporting.domain;

import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Vyapar-compatible billing table (Req 23.1). Vyapar's sales-import
 * expects one row per invoice <em>item</em>, with the invoice grouped by its
 * invoice number. This builder emits one row per order line item, repeating the
 * invoice-level fields (party, totals, payment type) on each of an order's rows,
 * which Vyapar collapses by the shared invoice number on import.
 *
 * <p>Column layout (documented, fixed order):
 * <pre>
 *   Date | Invoice Number | Party Name | Phone Number | Item Name | Quantity |
 *   Price/Unit | Amount | Total Amount | Received Amount | Balance Amount |
 *   Payment Type | State | Order Status
 * </pre>
 *
 * <p>Payment-type mapping to Vyapar: {@code FULLY_PAID → Cash},
 * {@code PARTIALLY_PAID → Partial}, {@code COD → Credit}.
 */
public class VyaparTableBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** The Vyapar column headers, in import order. */
    public static final List<String> HEADERS = List.of(
            "Date",
            "Invoice Number",
            "Party Name",
            "Phone Number",
            "Item Name",
            "Quantity",
            "Price/Unit",
            "Amount",
            "Total Amount",
            "Received Amount",
            "Balance Amount",
            "Payment Type",
            "State",
            "Order Status");

    /**
     * Builds the Vyapar table over the orders within the window (Req 23.1). When
     * the window contains no orders, the table is header-only (Req 23.2) — the
     * caller pairs this with a "no orders found" message.
     */
    public TabularData build(List<OrderReportRecord> orders, DateRange window) {
        ReportAggregator aggregator = new ReportAggregator();
        List<OrderReportRecord> windowed = aggregator.salespersonWise(orders, window);
        List<List<String>> rows = new ArrayList<>();
        for (OrderReportRecord o : windowed) {
            String paymentType = paymentType(o.paymentStatus());
            String date = o.orderDate() == null ? "" : o.orderDate().format(DATE);
            if (o.products().isEmpty()) {
                rows.add(row(date, o, "", "0", money(BigDecimal.ZERO), money(BigDecimal.ZERO), paymentType));
                continue;
            }
            for (OrderReportRecord.ProductLine line : o.products()) {
                rows.add(row(date, o, line.productName(), Integer.toString(line.quantity()),
                        money(line.rate()), money(line.lineTotal()), paymentType));
            }
        }
        return new TabularData(HEADERS, rows);
    }

    private static List<String> row(String date, OrderReportRecord o, String item, String qty,
                                    String priceUnit, String amount, String paymentType) {
        List<String> cells = new ArrayList<>();
        cells.add(date);
        cells.add(o.orderCode() == null ? "" : o.orderCode());
        cells.add(o.customerName() == null ? "" : o.customerName());
        cells.add(o.customerMobile() == null ? "" : o.customerMobile());
        cells.add(item);
        cells.add(qty);
        cells.add(priceUnit);
        cells.add(amount);
        cells.add(money(o.totalAmount()));
        cells.add(money(o.amountReceived()));
        cells.add(money(o.codAmount()));
        cells.add(paymentType);
        cells.add(o.state() == null ? "" : o.state());
        cells.add(o.orderStatus() == null ? "" : o.orderStatus().name());
        return cells;
    }

    private static String paymentType(PaymentStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case FULLY_PAID -> "Cash";
            case PARTIALLY_PAID -> "Partial";
            case COD -> "Credit";
        };
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }
}
