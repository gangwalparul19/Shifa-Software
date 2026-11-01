package com.shifa.oms.analytics;

import com.shifa.oms.analytics.domain.CustomerSegmenter;
import com.shifa.oms.analytics.domain.Facts;
import com.shifa.oms.analytics.domain.Facts.CustomerFact;
import com.shifa.oms.analytics.domain.Facts.LineFact;
import com.shifa.oms.analytics.domain.Facts.OrderFact;
import com.shifa.oms.analytics.domain.OverviewEngine;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ColumnInfo;
import com.shifa.oms.analytics.dto.GrowthAnalytics.DatasetInfo;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.reporting.CsvReportExporter;
import com.shifa.oms.reporting.ReportFilenames;
import com.shifa.oms.reporting.domain.TabularData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flat, documented datasets for analysts and data engineers: one row per order, per
 * order line, per customer, and per day. Each dataset is a plain CSV with stable
 * snake_case column names, ISO dates, raw numbers (no currency symbols) and a
 * one-way {@code customer_key} so the tables join without exposing mobile numbers.
 * Only the {@code customers} dataset carries names and mobiles, for outreach.
 */
@Service
public class AnalyticsDatasetService {

    /** Largest dataset the service will build, in rows. */
    static final int MAX_ROWS = 100_000;
    private static final int DEFAULT_RANGE_DAYS = 90;
    private static final int MAX_RANGE_DAYS = 366;

    /** The result of building a dataset file. */
    public record Export(String filename, String contentType, byte[] content, int rows) {
    }

    private final GrowthAnalyticsService analytics;
    private final CsvReportExporter csv;
    private final String keySalt;

    public AnalyticsDatasetService(GrowthAnalyticsService analytics, CsvReportExporter csv,
                                   @Value("${app.analytics.customer-key-salt:shifa-analytics}") String keySalt) {
        this.analytics = analytics;
        this.csv = csv;
        this.keySalt = keySalt;
    }

    // --- Catalog ----------------------------------------------------------

    /** The datasets on offer, with their columns documented. */
    public List<DatasetInfo> catalog() {
        return List.of(
                new DatasetInfo("orders", "Orders",
                        "Every order placed in the period, whatever its status, with its totals and delivery details.",
                        "One row per order", true, List.of(
                        col("order_id", "integer", "Database id of the order."),
                        col("order_code", "string", "Public order number, e.g. SHR-20261024-AB12."),
                        col("created_at", "timestamp", "When the order was placed, IST, ISO-8601."),
                        col("order_date", "date", "Order date (IST)."),
                        col("customer_key", "string", "One-way key for the customer; joins to the other datasets. Empty when the order has no real mobile."),
                        col("state", "string", "Destination state; blank for orders shipped abroad."),
                        col("city", "string", "Destination city."),
                        col("postal_code", "string", "Destination pincode."),
                        col("country", "string", "Destination country when outside India, else blank."),
                        col("order_status", "string", "Exact lifecycle status, e.g. DELIVERED, RTO."),
                        col("status_group", "string", "Business stage: PENDING_APPROVAL, PROCESSING, SHIPPED, DELIVERED, FAILED_RETURNED, CANCELLED or REJECTED."),
                        col("payment_status", "string", "FULLY_PAID, PARTIALLY_PAID or COD (pay on delivery)."),
                        col("order_source", "string", "How the order entered the system: SALESPERSON, SHOPIFY, STORE, ..."),
                        col("channel", "string", "PORTAL, SHOPIFY or STORE."),
                        col("lead_source", "string", "Where the customer came from; blank when not recorded."),
                        col("delivery_method", "string", "QUIKSHIPX, IN_HOUSE or STORE_PICKUP."),
                        col("total_amount", "decimal", "Order total in rupees, after discount and round-off."),
                        col("discount_amount", "decimal", "Discount given in rupees."),
                        col("amount_received", "decimal", "Money collected up front, in rupees."),
                        col("cod_amount", "decimal", "Amount to collect on delivery, in rupees."),
                        col("line_count", "integer", "Number of order lines."),
                        col("units", "integer", "Total quantity across lines."),
                        col("is_sale", "0/1", "1 unless the order was rejected, payment-rejected or cancelled."),
                        col("is_delivered", "0/1", "1 when the parcel was delivered."),
                        col("is_failed", "0/1", "1 when delivery failed, was refused or the parcel came back."),
                        col("is_first_order", "0/1", "1 when this is the customer's first sale ever."),
                        col("salesperson_id", "integer", "User id of whoever punched the order; blank for Shopify."))),
                new DatasetInfo("order_items", "Order items",
                        "Every order line of the orders placed in the period.",
                        "One row per order line", true, List.of(
                        col("order_id", "integer", "Joins to orders.order_id."),
                        col("order_code", "string", "Public order number."),
                        col("created_at", "timestamp", "When the order was placed, IST."),
                        col("customer_key", "string", "One-way customer key."),
                        col("order_status", "string", "Exact lifecycle status of the order."),
                        col("is_sale", "0/1", "1 unless the order was rejected, payment-rejected or cancelled."),
                        col("product_id", "integer", "Catalogue product id; blank for lines not matched to the catalogue."),
                        col("product_name", "string", "Product name as sold."),
                        col("quantity", "integer", "Units sold."),
                        col("rate", "decimal", "Unit price in rupees."),
                        col("line_total", "decimal", "Rate times quantity, before any order-level discount."))),
                new DatasetInfo("customers", "Customers",
                        "Every customer with sales to date, scored for recency, frequency and value. Contains names and mobile numbers.",
                        "One row per customer, all time", false, List.of(
                        col("customer_key", "string", "One-way customer key; joins to the other datasets."),
                        col("name", "string", "Name on the customer's latest order."),
                        col("mobile", "string", "10-digit mobile number."),
                        col("first_order_date", "date", "Date of the first sale."),
                        col("last_order_date", "date", "Date of the latest sale."),
                        col("orders", "integer", "Number of sales."),
                        col("revenue", "decimal", "Total of those sales, in rupees."),
                        col("avg_order_value", "decimal", "Revenue divided by orders."),
                        col("recency_days", "integer", "Days since the latest sale, as of the export."),
                        col("segment", "string", "CHAMPION, LOYAL, NEW, NEEDS_ATTENTION, AT_RISK, ONE_TIME_LAPSED or HIBERNATING."))),
                new DatasetInfo("daily_sales", "Daily sales",
                        "Orders, revenue and customers per day, with zero-filled days.",
                        "One row per day", true, List.of(
                        col("date", "date", "Order date (IST)."),
                        col("orders", "integer", "Sales placed that day."),
                        col("revenue", "decimal", "Revenue of those sales, in rupees."),
                        col("avg_order_value", "decimal", "Revenue divided by orders."),
                        col("new_customers", "integer", "Customers whose first sale was that day."),
                        col("lost_orders", "integer", "Orders rejected, payment-rejected or cancelled that day."))));
    }

    private static ColumnInfo col(String name, String type, String description) {
        return new ColumnInfo(name, type, description);
    }

    // --- Export -----------------------------------------------------------

    /**
     * Builds one dataset as CSV.
     *
     * @param from first order date (IST); ignored by the all-time customers dataset
     * @param to   last order date (IST), inclusive
     */
    @Transactional(readOnly = true)
    public Export export(String key, LocalDate from, LocalDate to) {
        String dataset = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        boolean allTime = "customers".equals(dataset);
        LocalDate end = to != null ? to : analytics.today();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (!allTime) {
            if (start.isAfter(end)) {
                throw new ValidationException("The start date must not be after the end date.");
            }
            if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
                throw new ValidationException("Choose a period of at most " + MAX_RANGE_DAYS + " days.");
            }
        }
        TabularData table = switch (dataset) {
            case "orders" -> orders(start, end);
            case "order_items" -> orderItems(start, end);
            case "customers" -> customers();
            case "daily_sales" -> dailySales(start, end);
            default -> throw new ResourceNotFoundException("Unknown dataset: " + key);
        };
        if (table.rowCount() > MAX_ROWS) {
            throw new ValidationException("That is more than " + MAX_ROWS + " rows. Choose a shorter period.");
        }
        String filename = ReportFilenames.of("data-" + dataset.replace('_', '-'),
                allTime ? null : start, allTime ? null : end, "csv");
        return new Export(filename, "text/csv", csv.export(table), table.rowCount());
    }

    private TabularData orders(LocalDate from, LocalDate to) {
        List<OrderFact> orders = analytics.loadOrders(from, to);
        Map<Long, long[]> lineTotals = new HashMap<>(); // lines, units
        for (LineFact l : analytics.loadLines(from, to)) {
            long[] t = lineTotals.computeIfAbsent(l.orderId(), k -> new long[2]);
            t[0]++;
            t[1] += l.quantity();
        }
        Map<String, LocalDateTime> firstOrder = firstOrders();
        List<List<String>> rows = new ArrayList<>();
        for (OrderFact o : orders) {
            long[] t = lineTotals.getOrDefault(o.id(), new long[2]);
            String mobile = o.customerMobile();
            OrderStatusGroup group = OrderStatusGroup.groupOf(o.status());
            rows.add(List.of(
                    String.valueOf(o.id()),
                    s(o.code()),
                    iso(o.createdAt()),
                    o.date() == null ? "" : o.date().toString(),
                    key(mobile),
                    s(o.state()),
                    s(o.city()),
                    s(o.postalCode()),
                    s(o.country()),
                    o.status() == null ? "" : o.status().name(),
                    group == null ? "" : group.name(),
                    o.payment() == null ? "" : o.payment().name(),
                    o.source() == null ? "" : o.source().name(),
                    OverviewEngine.channelKey(o.source()),
                    o.leadSource() == null ? "" : o.leadSource().name(),
                    o.deliveryMethod() == null ? "" : o.deliveryMethod().name(),
                    money(o.total()),
                    money(o.discount()),
                    money(o.received()),
                    money(o.cod()),
                    String.valueOf(t[0]),
                    String.valueOf(t[1]),
                    flag(o.isSale()),
                    flag(o.isDelivered()),
                    flag(o.isFailed()),
                    flag(mobile != null && o.isSale() && o.createdAt() != null
                            && o.createdAt().equals(firstOrder.get(mobile))),
                    o.createdBy() == null ? "" : String.valueOf(o.createdBy())));
        }
        return new TabularData(headers("orders"), rows);
    }

    private TabularData orderItems(LocalDate from, LocalDate to) {
        List<List<String>> rows = new ArrayList<>();
        for (LineFact l : analytics.loadLines(from, to)) {
            rows.add(List.of(
                    String.valueOf(l.orderId()),
                    s(l.orderCode()),
                    iso(l.createdAt()),
                    key(l.mobile()),
                    l.orderStatus() == null ? "" : l.orderStatus().name(),
                    flag(l.isSale()),
                    l.productId() == null ? "" : String.valueOf(l.productId()),
                    s(l.productName()),
                    String.valueOf(l.quantity()),
                    money(l.rate()),
                    money(l.lineTotal())));
        }
        return new TabularData(headers("order_items"), rows);
    }

    private TabularData customers() {
        LocalDate today = analytics.today();
        List<List<String>> rows = new ArrayList<>();
        for (CustomerFact c : analytics.loadCustomers()) {
            long recency = Math.max(0, ChronoUnit.DAYS.between(c.lastOrderAt().toLocalDate(), today));
            BigDecimal revenue = c.revenue() == null ? BigDecimal.ZERO : c.revenue();
            BigDecimal aov = c.orders() <= 0 ? BigDecimal.ZERO
                    : revenue.divide(BigDecimal.valueOf(c.orders()), 2, RoundingMode.HALF_UP);
            rows.add(List.of(
                    key(c.mobile()),
                    s(c.name()),
                    c.mobile(),
                    c.firstOrderAt().toLocalDate().toString(),
                    c.lastOrderAt().toLocalDate().toString(),
                    String.valueOf(c.orders()),
                    money(revenue),
                    money(aov),
                    String.valueOf(recency),
                    CustomerSegmenter.segmentOf(c.orders(), recency)));
        }
        return new TabularData(headers("customers"), rows);
    }

    private TabularData dailySales(LocalDate from, LocalDate to) {
        Map<String, LocalDateTime> firstOrder = firstOrders();
        Map<LocalDate, long[]> counts = new TreeMap<>();   // sales, new customers, lost
        Map<LocalDate, BigDecimal> revenue = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            counts.put(d, new long[3]);
            revenue.put(d, BigDecimal.ZERO);
        }
        for (OrderFact o : analytics.loadOrders(from, to)) {
            long[] c = counts.get(o.date());
            if (c == null) {
                continue;
            }
            if (o.isLost()) {
                c[2]++;
            } else if (o.isSale()) {
                c[0]++;
                revenue.merge(o.date(), o.total() == null ? BigDecimal.ZERO : o.total(), BigDecimal::add);
                String mobile = o.customerMobile();
                if (mobile != null && o.createdAt() != null && o.createdAt().equals(firstOrder.get(mobile))) {
                    c[1]++;
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : counts.entrySet()) {
            BigDecimal rev = revenue.get(e.getKey());
            BigDecimal aov = e.getValue()[0] == 0 ? BigDecimal.ZERO
                    : rev.divide(BigDecimal.valueOf(e.getValue()[0]), 2, RoundingMode.HALF_UP);
            rows.add(List.of(
                    e.getKey().toString(),
                    String.valueOf(e.getValue()[0]),
                    money(rev),
                    money(aov),
                    String.valueOf(e.getValue()[1]),
                    String.valueOf(e.getValue()[2])));
        }
        return new TabularData(headers("daily_sales"), rows);
    }

    // --- Helpers ----------------------------------------------------------

    private List<String> headers(String dataset) {
        for (DatasetInfo d : catalog()) {
            if (d.key().equals(dataset)) {
                return d.columns().stream().map(ColumnInfo::name).toList();
            }
        }
        throw new IllegalStateException("No catalog entry for " + dataset);
    }

    private Map<String, LocalDateTime> firstOrders() {
        Map<String, LocalDateTime> first = new HashMap<>();
        for (CustomerFact c : analytics.loadCustomers()) {
            first.put(c.mobile(), c.firstOrderAt());
        }
        return first;
    }

    private String key(String mobile) {
        return Facts.customerKey(mobile, keySalt);
    }

    private static String s(String value) {
        return value == null ? "" : value;
    }

    /** ISO-8601 local date-time with seconds, e.g. 2026-10-24T12:00:00 (IST). */
    private static String iso(LocalDateTime t) {
        return t == null ? "" : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t.withNano(0));
    }

    private static String money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String flag(boolean b) {
        return b ? "1" : "0";
    }
}
