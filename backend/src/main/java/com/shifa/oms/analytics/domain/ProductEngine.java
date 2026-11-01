package com.shifa.oms.analytics.domain;

import com.shifa.oms.analytics.domain.Facts.LineFact;
import com.shifa.oms.analytics.dto.GrowthAnalytics.AffinityPair;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductPerformanceReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure calculations behind the Products tab: what sells, how concentrated revenue is
 * (ABC classes), how each product moved against the previous period, how often buyers
 * come back for it, and which products are bought together. Counts sales only, using
 * each line's total before any order-level discount.
 */
public final class ProductEngine {

    /** Products listed in the report. */
    public static final int MAX_PRODUCTS = 100;
    /** Affinity pairs listed. */
    public static final int MAX_PAIRS = 10;
    /** Orders with more distinct products than this are skipped when pairing (the pair count grows quadratically). */
    private static final int MAX_PRODUCTS_PER_ORDER_FOR_PAIRS = 12;

    private ProductEngine() {
    }

    private static final class Agg {
        Long productId;
        String name;
        long units;
        BigDecimal revenue = BigDecimal.ZERO;
        final Set<Long> orderIds = new HashSet<>();
        final Map<String, Set<Long>> ordersByBuyer = new HashMap<>();
    }

    /** Builds the report from the period's lines and the previous period's lines. */
    public static ProductPerformanceReport build(LocalDate from, LocalDate to,
                                                 List<LineFact> lines, List<LineFact> previousLines) {
        Map<String, Agg> products = new LinkedHashMap<>();
        Map<Long, Set<String>> productsByOrder = new HashMap<>();
        long totalUnits = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        for (LineFact l : lines) {
            if (!l.isSale()) {
                continue;
            }
            Agg a = products.computeIfAbsent(l.productKey(), k -> new Agg());
            if (a.productId == null) {
                a.productId = l.productId();
            }
            if (a.name == null || a.name.isBlank()) {
                a.name = l.productName();
            }
            a.units += l.quantity();
            a.revenue = a.revenue.add(AnalyticsMath.nz(l.lineTotal()));
            a.orderIds.add(l.orderId());
            if (Facts.isRealMobile(l.mobile())) {
                a.ordersByBuyer.computeIfAbsent(l.mobile(), k -> new HashSet<>()).add(l.orderId());
            }
            productsByOrder.computeIfAbsent(l.orderId(), k -> new HashSet<>()).add(l.productKey());
            totalUnits += l.quantity();
            totalRevenue = totalRevenue.add(AnalyticsMath.nz(l.lineTotal()));
        }

        Map<String, long[]> previousUnits = new HashMap<>();
        Map<String, BigDecimal> previousRevenue = new HashMap<>();
        for (LineFact l : previousLines) {
            if (!l.isSale()) {
                continue;
            }
            previousUnits.computeIfAbsent(l.productKey(), k -> new long[1])[0] += l.quantity();
            previousRevenue.merge(l.productKey(), AnalyticsMath.nz(l.lineTotal()), BigDecimal::add);
        }

        List<Map.Entry<String, Agg>> ranked = new ArrayList<>(products.entrySet());
        ranked.sort((x, y) -> y.getValue().revenue.compareTo(x.getValue().revenue));

        List<ProductRow> rows = new ArrayList<>();
        double cumulativeBefore = 0;
        double top3 = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Agg a = ranked.get(i).getValue();
            String key = ranked.get(i).getKey();
            double share = totalRevenue.signum() <= 0 ? 0 : a.revenue.doubleValue() * 100.0 / totalRevenue.doubleValue();
            String abc = cumulativeBefore < 80.0 ? "A" : cumulativeBefore < 95.0 ? "B" : "C";
            cumulativeBefore += share;
            if (i < 3) {
                top3 += share;
            }
            long buyers = a.ordersByBuyer.size();
            long repeatBuyers = a.ordersByBuyer.values().stream().filter(o -> o.size() >= 2).count();
            long prevUnits = previousUnits.getOrDefault(key, new long[1])[0];
            BigDecimal prevRevenue = previousRevenue.getOrDefault(key, BigDecimal.ZERO);
            Double growth = prevRevenue.signum() > 0
                    ? AnalyticsMath.round1((a.revenue.doubleValue() - prevRevenue.doubleValue()) * 100.0
                            / prevRevenue.doubleValue())
                    : null;
            if (rows.size() < MAX_PRODUCTS) {
                rows.add(new ProductRow(
                        a.productId,
                        a.name == null ? "Product" : a.name,
                        a.units,
                        AnalyticsMath.money(a.revenue),
                        a.orderIds.size(),
                        buyers,
                        AnalyticsMath.pct(repeatBuyers, buyers),
                        AnalyticsMath.round1(share),
                        AnalyticsMath.round1(Math.min(100.0, cumulativeBefore)),
                        abc,
                        prevUnits,
                        AnalyticsMath.money(prevRevenue),
                        growth));
            }
        }

        long orderCount = productsByOrder.size();
        long multi = productsByOrder.values().stream().filter(s -> s.size() >= 2).count();
        return new ProductPerformanceReport(
                from,
                to,
                totalUnits,
                AnalyticsMath.money(totalRevenue),
                products.size(),
                AnalyticsMath.round1(Math.min(100.0, top3)),
                multi,
                AnalyticsMath.pct(multi, orderCount),
                orderCount == 0 ? 0.0 : AnalyticsMath.round1((double) totalUnits / orderCount),
                rows,
                pairs(products, productsByOrder, orderCount));
    }

    /** The products most often bought together, with support and lift. */
    private static List<AffinityPair> pairs(Map<String, Agg> products, Map<Long, Set<String>> productsByOrder,
                                            long orderCount) {
        Map<String, Integer> pairCounts = new HashMap<>();
        Map<String, Integer> productCounts = new HashMap<>();
        for (Set<String> keys : productsByOrder.values()) {
            for (String k : keys) {
                productCounts.merge(k, 1, Integer::sum);
            }
            if (keys.size() < 2 || keys.size() > MAX_PRODUCTS_PER_ORDER_FOR_PAIRS) {
                continue;
            }
            List<String> sorted = new ArrayList<>(new TreeSet<>(keys));
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    pairCounts.merge(sorted.get(i) + "\u0000" + sorted.get(j), 1, Integer::sum);
                }
            }
        }
        List<AffinityPair> pairs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pairCounts.entrySet()) {
            if (e.getValue() < 2) {
                continue;
            }
            String[] parts = e.getKey().split("\u0000");
            double lift = (double) e.getValue() * orderCount
                    / ((double) productCounts.get(parts[0]) * productCounts.get(parts[1]));
            pairs.add(new AffinityPair(
                    nameOf(products, parts[0]),
                    nameOf(products, parts[1]),
                    e.getValue(),
                    AnalyticsMath.pct(e.getValue(), orderCount),
                    AnalyticsMath.round2(lift)));
        }
        pairs.sort(Comparator.comparingLong(AffinityPair::orders).reversed()
                .thenComparing(Comparator.comparingDouble(AffinityPair::lift).reversed()));
        return pairs.size() > MAX_PAIRS ? new ArrayList<>(pairs.subList(0, MAX_PAIRS)) : pairs;
    }

    private static String nameOf(Map<String, Agg> products, String key) {
        Agg a = products.get(key);
        return a == null || a.name == null ? "Product" : a.name;
    }
}
