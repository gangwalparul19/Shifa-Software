package com.shifa.oms.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure sales-executive &times; product analytics (client request): the detail
 * report, the executive-tracker grid, and the top-N executives per category /
 * product with repeat-customer signals.
 *
 * <p>All figures are computed over <em>revenue</em> orders only (rejected,
 * payment-rejected and cancelled orders are not sales), inside the report
 * window — the same definition every other money figure uses.
 *
 * <p><strong>Definitions.</strong>
 * <ul>
 *   <li><em>Total sales</em> of a line is its {@code lineTotal} (rate &times;
 *       quantity, GST-inclusive, before any order-level discount).</li>
 *   <li><em>Gross sale</em> of an executive is the sum of their line totals;
 *       <em>net sale</em> is the sum of their order totals, i.e. after order
 *       discount and round-off. The gap between them is the discount given.</li>
 *   <li>A <em>customer</em> is identified by mobile number (falling back to
 *       name); an order that identifies nobody counts as its own customer so
 *       anonymous orders are never merged into a false "repeat".</li>
 *   <li>A <em>repeat customer</em> bought in at least two distinct orders from
 *       that executive within that category/product <em>inside the selected
 *       window</em>. Order history before the window is not visible here, so
 *       use a wide window (60&ndash;90+ days) for retention decisions.</li>
 *   <li>Top-N rankings consider only orders punched by an executive
 *       ({@code salespersonId != null}); unattributed orders (e.g. Shopify) are
 *       not an executive and would otherwise crowd real people out. The share
 *       percentage is therefore the share of what <em>executives</em> sold.</li>
 * </ul>
 */
public class ExecutiveProductAnalytics {

    /** How many executives the "top" reports list per category / product. */
    public static final int TOP_N = 3;

    /** The label a product with no category is reported under. */
    public static final String UNCATEGORISED = "Uncategorised";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ReportAggregator aggregator;

    public ExecutiveProductAnalytics(ReportAggregator aggregator) {
        this.aggregator = aggregator;
    }

    // --- Row shapes ----------------------------------------------------------

    /** One (executive, product) row of the detail report. */
    public record DetailRow(Long salespersonId, String category, String productName,
                            long orders, long customers, long quantity, BigDecimal totalSales,
                            BigDecimal avgPrice, BigDecimal shareOfExecutivePct) {
    }

    /** One executive's row of the grid, with the quantity sold per product. */
    public record MatrixRow(Long salespersonId, long orders, BigDecimal grossSale, BigDecimal netSale,
                            long totalQuantity, int distinctProducts,
                            Map<String, Long> quantityByProduct) {
    }

    /** The executive grid: the product columns (alphabetical) and one row per executive. */
    public record Matrix(List<String> products, List<MatrixRow> rows) {
    }

    /** One ranked executive within a category or product. */
    public record TopRow(String group, int rank, Long salespersonId, long orders, long customers,
                         long quantity, BigDecimal totalSales, BigDecimal shareOfGroupPct,
                         long repeatCustomers, BigDecimal repeatRatePct) {
    }

    // --- Accumulator ---------------------------------------------------------

    /** Running totals for one (executive, group) cell. */
    private static final class Cell {
        final Set<Integer> orderIndexes = new HashSet<>();
        final Map<String, Set<Integer>> ordersByCustomer = new HashMap<>();
        long quantity;
        BigDecimal sales = BigDecimal.ZERO;

        void add(int orderIndex, String customerKey, OrderReportRecord.ProductLine line) {
            orderIndexes.add(orderIndex);
            ordersByCustomer.computeIfAbsent(customerKey, k -> new HashSet<>()).add(orderIndex);
            quantity += line.quantity();
            sales = sales.add(line.lineTotal());
        }

        long customers() {
            return ordersByCustomer.size();
        }

        long repeatCustomers() {
            long n = 0;
            for (Set<Integer> orders : ordersByCustomer.values()) {
                if (orders.size() >= 2) {
                    n++;
                }
            }
            return n;
        }
    }

    private record Key(Long salespersonId, String group) {
    }

    // --- Detail --------------------------------------------------------------

    /**
     * One row per (executive, product) with category, orders, distinct customers,
     * quantity, total sales, average realised price per unit, and the product's
     * share of that executive's sales. Ordered by executive id (nulls last), then
     * total sales descending, then product name.
     */
    public List<DetailRow> detail(List<OrderReportRecord> orders, DateRange window) {
        Map<Key, Cell> cells = new LinkedHashMap<>();
        Map<Key, String> categoryOf = new HashMap<>();
        Map<Long, BigDecimal> executiveTotal = new HashMap<>();

        List<OrderReportRecord> revenue = aggregator.revenueWithin(orders, window);
        for (int i = 0; i < revenue.size(); i++) {
            OrderReportRecord o = revenue.get(i);
            String customer = customerKey(o, i);
            for (OrderReportRecord.ProductLine line : o.products()) {
                Key key = new Key(o.salespersonId(), productName(line));
                cells.computeIfAbsent(key, k -> new Cell()).add(i, customer, line);
                categoryOf.putIfAbsent(key, categoryName(line));
                executiveTotal.merge(o.salespersonId(), line.lineTotal(), BigDecimal::add);
            }
        }

        List<DetailRow> rows = new ArrayList<>();
        for (Map.Entry<Key, Cell> e : cells.entrySet()) {
            Key key = e.getKey();
            Cell cell = e.getValue();
            BigDecimal total = executiveTotal.getOrDefault(key.salespersonId(), BigDecimal.ZERO);
            rows.add(new DetailRow(
                    key.salespersonId(), categoryOf.get(key), key.group(),
                    cell.orderIndexes.size(), cell.customers(), cell.quantity,
                    scale(cell.sales),
                    cell.quantity == 0
                            ? zero()
                            : cell.sales.divide(BigDecimal.valueOf(cell.quantity), 2, RoundingMode.HALF_UP),
                    percent(cell.sales, total)));
        }
        rows.sort(Comparator
                .comparing((DetailRow r) -> r.salespersonId() == null ? Long.MAX_VALUE : r.salespersonId())
                .thenComparing(Comparator.comparing(DetailRow::totalSales).reversed())
                .thenComparing(DetailRow::productName));
        return rows;
    }

    // --- Grid (the client's Excel executive tracker) -------------------------

    /**
     * The executive &times; product grid: one row per executive with orders,
     * gross sale, net sale, total quantity, distinct products and the quantity
     * sold of each product. Product columns are every product sold in the window,
     * alphabetical; executives are ordered by gross sale descending.
     */
    public Matrix matrix(List<OrderReportRecord> orders, DateRange window) {
        TreeMap<String, Boolean> productColumns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<Long, Map<String, Long>> quantity = new LinkedHashMap<>();
        Map<Long, BigDecimal> gross = new HashMap<>();
        Map<Long, BigDecimal> net = new HashMap<>();
        Map<Long, Long> orderCount = new HashMap<>();

        for (OrderReportRecord o : aggregator.revenueWithin(orders, window)) {
            Long sp = o.salespersonId();
            Map<String, Long> perProduct = quantity.computeIfAbsent(sp, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
            orderCount.merge(sp, 1L, Long::sum);
            net.merge(sp, o.totalAmount(), BigDecimal::add);
            for (OrderReportRecord.ProductLine line : o.products()) {
                String name = productName(line);
                productColumns.put(name, Boolean.TRUE);
                perProduct.merge(name, (long) line.quantity(), Long::sum);
                gross.merge(sp, line.lineTotal(), BigDecimal::add);
            }
            gross.putIfAbsent(sp, BigDecimal.ZERO);
        }

        List<MatrixRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Long>> e : quantity.entrySet()) {
            Long sp = e.getKey();
            Map<String, Long> perProduct = e.getValue();
            long total = 0;
            int distinct = 0;
            for (long q : perProduct.values()) {
                total += q;
                if (q > 0) {
                    distinct++;
                }
            }
            rows.add(new MatrixRow(sp, orderCount.getOrDefault(sp, 0L),
                    scale(gross.getOrDefault(sp, BigDecimal.ZERO)),
                    scale(net.getOrDefault(sp, BigDecimal.ZERO)),
                    total, distinct, perProduct));
        }
        rows.sort(Comparator.comparing(MatrixRow::grossSale).reversed()
                .thenComparing(r -> r.salespersonId() == null ? Long.MAX_VALUE : r.salespersonId()));
        return new Matrix(List.copyOf(productColumns.keySet()), rows);
    }

    // --- Top-N ---------------------------------------------------------------

    /** Top {@value #TOP_N} executives per product category (uncategorised listed last). */
    public List<TopRow> topByCategory(List<OrderReportRecord> orders, DateRange window) {
        return top(orders, window, true);
    }

    /** Top {@value #TOP_N} executives per individual product. */
    public List<TopRow> topByProduct(List<OrderReportRecord> orders, DateRange window) {
        return top(orders, window, false);
    }

    private List<TopRow> top(List<OrderReportRecord> orders, DateRange window, boolean byCategory) {
        Map<Key, Cell> cells = new LinkedHashMap<>();
        Map<String, BigDecimal> groupTotal = new HashMap<>();

        List<OrderReportRecord> revenue = aggregator.revenueWithin(orders, window);
        for (int i = 0; i < revenue.size(); i++) {
            OrderReportRecord o = revenue.get(i);
            if (o.salespersonId() == null) {
                continue; // not an executive — would crowd real people out of the ranking
            }
            String customer = customerKey(o, i);
            for (OrderReportRecord.ProductLine line : o.products()) {
                String group = byCategory ? categoryName(line) : productName(line);
                cells.computeIfAbsent(new Key(o.salespersonId(), group), k -> new Cell())
                        .add(i, customer, line);
                groupTotal.merge(group, line.lineTotal(), BigDecimal::add);
            }
        }

        Map<String, List<Map.Entry<Key, Cell>>> byGroup = new HashMap<>();
        for (Map.Entry<Key, Cell> e : cells.entrySet()) {
            byGroup.computeIfAbsent(e.getKey().group(), k -> new ArrayList<>()).add(e);
        }

        List<String> groups = new ArrayList<>(byGroup.keySet());
        groups.sort(Comparator
                .comparing((String g) -> byCategory && UNCATEGORISED.equals(g))
                .thenComparing(Comparator.comparing(
                        (String g) -> groupTotal.getOrDefault(g, BigDecimal.ZERO)).reversed())
                .thenComparing(g -> g));

        List<TopRow> out = new ArrayList<>();
        for (String group : groups) {
            List<Map.Entry<Key, Cell>> entries = byGroup.get(group);
            entries.sort(Comparator
                    .comparing((Map.Entry<Key, Cell> e) -> e.getValue().sales).reversed()
                    .thenComparing(Comparator.comparingLong((Map.Entry<Key, Cell> e) -> e.getValue().quantity).reversed())
                    .thenComparing(e -> e.getKey().salespersonId()));
            BigDecimal total = groupTotal.getOrDefault(group, BigDecimal.ZERO);
            int rank = 0;
            for (Map.Entry<Key, Cell> e : entries) {
                if (rank >= TOP_N) {
                    break;
                }
                rank++;
                Cell c = e.getValue();
                out.add(new TopRow(group, rank, e.getKey().salespersonId(),
                        c.orderIndexes.size(), c.customers(), c.quantity, scale(c.sales),
                        percent(c.sales, total), c.repeatCustomers(),
                        percent(BigDecimal.valueOf(c.repeatCustomers()), BigDecimal.valueOf(c.customers()))));
            }
        }
        return out;
    }

    // --- Helpers -------------------------------------------------------------

    private static String productName(OrderReportRecord.ProductLine line) {
        String name = line.productName();
        return name == null || name.isBlank() ? "(unnamed)" : name.trim();
    }

    private static String categoryName(OrderReportRecord.ProductLine line) {
        String category = line.category();
        return category == null || category.isBlank() ? UNCATEGORISED : category.trim();
    }

    /** Stable customer identity: mobile, else name, else a per-order key (never merged). */
    private static String customerKey(OrderReportRecord o, int orderIndex) {
        String mobile = o.customerMobile();
        if (mobile != null && !mobile.isBlank()) {
            return "m:" + mobile.trim();
        }
        String name = o.customerName();
        if (name != null && !name.isBlank()) {
            return "n:" + name.trim().toLowerCase(Locale.ROOT);
        }
        return "o:" + orderIndex;
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return zero();
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
