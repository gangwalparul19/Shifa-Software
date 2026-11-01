package com.shifa.oms.analytics.domain;

import com.shifa.oms.analytics.domain.Facts.OrderFact;
import com.shifa.oms.analytics.dto.GrowthAnalytics.GeoRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.HeatMap;
import com.shifa.oms.analytics.dto.GrowthAnalytics.Kpis;
import com.shifa.oms.analytics.dto.GrowthAnalytics.MixRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.TrendPoint;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure calculations behind the Overview tab: period KPIs, the sales trend, channel /
 * lead-source / payment mixes, destination ranking and the weekday x hour heat map.
 * Every figure counts sales only (rejected, payment-rejected and cancelled orders are
 * "lost orders" and are reported separately).
 */
public final class OverviewEngine {

    /** Shown for an order that carries no lead source. */
    public static final String NOT_RECORDED = "NOT_RECORDED";

    private OverviewEngine() {
    }

    /**
     * Headline numbers for one period.
     *
     * @param orders         every order placed in the period (all statuses)
     * @param firstOrderByMobile each customer's first sale ever, used to split new from returning
     * @param from           start of the period (inclusive)
     */
    public static Kpis kpis(List<OrderFact> orders, Map<String, LocalDateTime> firstOrderByMobile,
                            LocalDateTime from) {
        long sales = 0;
        long lost = 0;
        long discounted = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal returningRevenue = BigDecimal.ZERO;
        Set<String> customers = new HashSet<>();
        Set<String> newCustomers = new HashSet<>();
        Set<String> returning = new HashSet<>();

        for (OrderFact o : orders) {
            if (o.isLost()) {
                lost++;
                continue;
            }
            if (!o.isSale()) {
                continue;
            }
            sales++;
            revenue = revenue.add(AnalyticsMath.nz(o.total()));
            if (AnalyticsMath.nz(o.discount()).signum() > 0) {
                discounted++;
                discountTotal = discountTotal.add(o.discount());
            }
            String mobile = o.customerMobile();
            if (mobile == null) {
                continue;
            }
            customers.add(mobile);
            LocalDateTime first = firstOrderByMobile.get(mobile);
            boolean isNew = first == null || !first.isBefore(from);
            if (isNew) {
                newCustomers.add(mobile);
            } else {
                returning.add(mobile);
                returningRevenue = returningRevenue.add(AnalyticsMath.nz(o.total()));
            }
        }
        long placed = sales + lost;
        return new Kpis(
                sales,
                AnalyticsMath.money(revenue),
                AnalyticsMath.average(revenue, sales),
                customers.size(),
                newCustomers.size(),
                returning.size(),
                AnalyticsMath.money(returningRevenue),
                AnalyticsMath.pct(returningRevenue.doubleValue(), revenue.doubleValue()),
                AnalyticsMath.money(discountTotal),
                AnalyticsMath.pct(discounted, sales),
                lost,
                AnalyticsMath.pct(lost, placed));
    }

    /**
     * The sales trend over {@code [from, to]}, one point per day (or per week, starting
     * Monday, when {@code weekly}), with empty buckets filled so the series is continuous.
     */
    public static List<TrendPoint> trend(List<OrderFact> orders, Map<String, LocalDateTime> firstOrderByMobile,
                                         LocalDate from, LocalDate to, boolean weekly) {
        Map<LocalDate, long[]> orderCounts = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> revenue = new LinkedHashMap<>();
        LocalDate cursor = bucket(from, weekly);
        LocalDate end = bucket(to, weekly);
        while (!cursor.isAfter(end)) {
            orderCounts.put(cursor, new long[2]);
            revenue.put(cursor, BigDecimal.ZERO);
            cursor = weekly ? cursor.plusWeeks(1) : cursor.plusDays(1);
        }
        Set<String> counted = new HashSet<>();
        List<OrderFact> sales = orders.stream()
                .filter(OrderFact::isSale)
                .sorted(Comparator.comparing(OrderFact::createdAt).thenComparingLong(OrderFact::id))
                .toList();
        for (OrderFact o : sales) {
            LocalDate key = bucket(o.date(), weekly);
            long[] cell = orderCounts.get(key);
            if (cell == null) {
                continue;
            }
            cell[0]++;
            revenue.merge(key, AnalyticsMath.nz(o.total()), BigDecimal::add);
            String mobile = o.customerMobile();
            if (mobile != null && counted.add(mobile)) {
                LocalDateTime first = firstOrderByMobile.get(mobile);
                if (first != null && first.equals(o.createdAt())) {
                    cell[1]++;
                }
            }
        }
        List<TrendPoint> points = new ArrayList<>(orderCounts.size());
        for (Map.Entry<LocalDate, long[]> e : orderCounts.entrySet()) {
            points.add(new TrendPoint(e.getKey(), e.getValue()[0],
                    AnalyticsMath.money(revenue.get(e.getKey())), e.getValue()[1]));
        }
        return points;
    }

    private static LocalDate bucket(LocalDate date, boolean weekly) {
        return weekly ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
    }

    /** Orders where the buyer's channel is Shopify, Store (POS) or our own online sales (Portal). */
    public static String channelKey(OrderSource source) {
        if (source == OrderSource.SHOPIFY) {
            return "SHOPIFY";
        }
        if (source == OrderSource.STORE) {
            return "STORE";
        }
        return "PORTAL";
    }

    public static String channelLabel(String key) {
        return switch (key) {
            case "SHOPIFY" -> "Shopify";
            case "STORE" -> "Store (POS)";
            default -> "Portal (sales team)";
        };
    }

    public static String paymentLabel(PaymentStatus status) {
        if (status == null) {
            return "Unknown";
        }
        return switch (status) {
            case FULLY_PAID -> "Prepaid";
            case PARTIALLY_PAID -> "Part paid";
            case COD -> "Pay on delivery";
        };
    }

    public static String leadSourceLabel(String key) {
        if (NOT_RECORDED.equals(key)) {
            return "Not recorded";
        }
        return switch (key) {
            case "SHOPIFY_UPSELL" -> "Shopify upsell";
            case "SHOPIFY_ABANDONMENT_SALE" -> "Shopify abandonment sale";
            case "INBOUND_CALLS" -> "Inbound calls";
            case "REPEAT_CUSTOMER" -> "Repeat customer";
            case "COUNTER_SALE" -> "Counter sale";
            case "WHATSAPP" -> "WhatsApp";
            default -> AnalyticsMath.humanize(key);
        };
    }

    /**
     * A mix table: sales grouped by {@code keyOf}, largest revenue first. The success rate
     * uses the group's orders that already reached a delivery outcome.
     */
    public static List<MixRow> mix(List<OrderFact> orders, Function<OrderFact, String> keyOf,
                                   Function<String, String> labelOf) {
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        Map<String, long[]> counts = new LinkedHashMap<>(); // orders, delivered, failed
        BigDecimal totalRevenue = BigDecimal.ZERO;
        for (OrderFact o : orders) {
            if (!o.isSale()) {
                continue;
            }
            String key = keyOf.apply(o);
            revenue.merge(key, AnalyticsMath.nz(o.total()), BigDecimal::add);
            long[] c = counts.computeIfAbsent(key, k -> new long[3]);
            c[0]++;
            if (o.isDelivered()) {
                c[1]++;
            } else if (o.isFailed()) {
                c[2]++;
            }
            totalRevenue = totalRevenue.add(AnalyticsMath.nz(o.total()));
        }
        List<MixRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            BigDecimal rev = revenue.get(e.getKey());
            long outcomes = c[1] + c[2];
            rows.add(new MixRow(
                    e.getKey(),
                    labelOf.apply(e.getKey()),
                    c[0],
                    AnalyticsMath.money(rev),
                    AnalyticsMath.pct(rev.doubleValue(), totalRevenue.doubleValue()),
                    AnalyticsMath.average(rev, c[0]),
                    outcomes == 0 ? null : AnalyticsMath.pct(c[1], outcomes)));
        }
        rows.sort(Comparator.comparing(MixRow::revenue).reversed());
        return rows;
    }

    /** Where sales go: grouped by {@code keyOf} (state or city), the biggest {@code limit} by revenue. */
    public static List<GeoRow> geo(List<OrderFact> orders, Function<OrderFact, String> keyOf, int limit) {
        Map<String, String> display = new LinkedHashMap<>();
        Map<String, BigDecimal> revenue = new LinkedHashMap<>();
        Map<String, long[]> orderCounts = new LinkedHashMap<>();
        Map<String, Set<String>> customers = new LinkedHashMap<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        for (OrderFact o : orders) {
            if (!o.isSale()) {
                continue;
            }
            String name = keyOf.apply(o);
            String key = name.toLowerCase(Locale.ROOT);
            display.putIfAbsent(key, name);
            revenue.merge(key, AnalyticsMath.nz(o.total()), BigDecimal::add);
            orderCounts.computeIfAbsent(key, k -> new long[1])[0]++;
            String mobile = o.customerMobile();
            if (mobile != null) {
                customers.computeIfAbsent(key, k -> new HashSet<>()).add(mobile);
            }
            totalRevenue = totalRevenue.add(AnalyticsMath.nz(o.total()));
        }
        List<GeoRow> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : orderCounts.entrySet()) {
            BigDecimal rev = revenue.get(e.getKey());
            rows.add(new GeoRow(
                    display.get(e.getKey()),
                    e.getValue()[0],
                    AnalyticsMath.money(rev),
                    AnalyticsMath.pct(rev.doubleValue(), totalRevenue.doubleValue()),
                    customers.getOrDefault(e.getKey(), Set.of()).size(),
                    AnalyticsMath.average(rev, e.getValue()[0])));
        }
        rows.sort(Comparator.comparing(GeoRow::revenue).reversed());
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** The destination state of an order, or a label for orders shipped abroad / with no state. */
    public static String stateOf(OrderFact o) {
        String state = o.state() == null ? "" : o.state().trim();
        if (!state.isEmpty()) {
            return state;
        }
        String country = o.country() == null ? "" : o.country().trim();
        if (!country.isEmpty() && !"india".equalsIgnoreCase(country)) {
            return "Outside India";
        }
        return "(unknown)";
    }

    /** The destination city of an order, or "(unknown)". */
    public static String cityOf(OrderFact o) {
        String city = o.city() == null ? "" : o.city().trim();
        return city.isEmpty() ? "(unknown)" : city;
    }

    /** Orders by weekday (Monday first) and hour of day, counting sales only. */
    public static HeatMap heatmap(List<OrderFact> orders) {
        long[][] cells = new long[7][24];
        long[] byDay = new long[7];
        long[] byHour = new long[24];
        for (OrderFact o : orders) {
            if (!o.isSale() || o.createdAt() == null) {
                continue;
            }
            int day = o.createdAt().getDayOfWeek().getValue() - 1;
            int hour = o.createdAt().getHour();
            cells[day][hour]++;
            byDay[day]++;
            byHour[hour]++;
        }
        long max = 0;
        for (long[] row : cells) {
            for (long v : row) {
                max = Math.max(max, v);
            }
        }
        List<List<Long>> grid = new ArrayList<>(7);
        for (long[] row : cells) {
            List<Long> line = new ArrayList<>(24);
            for (long v : row) {
                line.add(v);
            }
            grid.add(line);
        }
        return new HeatMap(grid, boxed(byDay), boxed(byHour), peak(byDay), peak(byHour), max);
    }

    private static List<Long> boxed(long[] values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long v : values) {
            list.add(v);
        }
        return list;
    }

    /** Index of the largest value, or -1 when every value is zero. */
    private static int peak(long[] values) {
        int best = -1;
        long max = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > max) {
                max = values[i];
                best = i;
            }
        }
        return best;
    }
}
