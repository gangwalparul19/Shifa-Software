package com.shifa.oms.analytics.domain;

import com.shifa.oms.analytics.domain.Facts.CustomerFact;
import com.shifa.oms.analytics.dto.GrowthAnalytics.BandRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.CustomerRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.CustomerSegmentsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.SegmentRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits every customer into one engagement segment from how recently and how often
 * they bought (a rules-based RFM view), so the team knows who to thank, nudge or win
 * back. The rules are fixed and explainable, and every customer lands in exactly one
 * segment:
 *
 * <ul>
 *   <li>one order: NEW (within 30 days), NEEDS_ATTENTION (31-90 days), ONE_TIME_LAPSED (over 90);</li>
 *   <li>two or more orders: CHAMPION (4+ orders, within 60 days), LOYAL (within 60 days),
 *       AT_RISK (61-180 days), HIBERNATING (over 180).</li>
 * </ul>
 */
public final class CustomerSegmenter {

    /** How many customers each segment lists as a ready-made call list. */
    public static final int SEGMENT_SAMPLE = 10;
    /** How many customers the all-time top list shows. */
    public static final int TOP_CUSTOMERS = 20;

    private CustomerSegmenter() {
    }

    /** Segment keys in display order. */
    public static final List<String> SEGMENT_ORDER = List.of(
            "CHAMPION", "LOYAL", "NEW", "NEEDS_ATTENTION", "AT_RISK", "ONE_TIME_LAPSED", "HIBERNATING");

    /** The segment a customer belongs to. */
    public static String segmentOf(long orders, long recencyDays) {
        if (orders <= 1) {
            if (recencyDays <= 30) {
                return "NEW";
            }
            return recencyDays <= 90 ? "NEEDS_ATTENTION" : "ONE_TIME_LAPSED";
        }
        if (recencyDays <= 60) {
            return orders >= 4 ? "CHAMPION" : "LOYAL";
        }
        return recencyDays <= 180 ? "AT_RISK" : "HIBERNATING";
    }

    private record Meta(String label, String description, String action) {
    }

    private static final Map<String, Meta> META = Map.of(
            "CHAMPION", new Meta("Champions",
                    "Bought 4+ times and ordered in the last 60 days.",
                    "Thank them, ask for referrals and reviews, and offer early access to new products."),
            "LOYAL", new Meta("Loyal",
                    "Bought more than once and ordered in the last 60 days.",
                    "Keep the habit going with a reorder reminder and a bundle offer."),
            "NEW", new Meta("New",
                    "First order within the last 30 days.",
                    "Send a welcome note and usage tips; a second order is the goal."),
            "NEEDS_ATTENTION", new Meta("Needs attention",
                    "One order, 31-90 days ago.",
                    "Nudge a second order now, while they still remember the product."),
            "AT_RISK", new Meta("At risk",
                    "Repeat customers who last ordered 61-180 days ago.",
                    "Run a personal win-back call or WhatsApp with a reorder offer."),
            "ONE_TIME_LAPSED", new Meta("One-time, lapsed",
                    "One order, more than 90 days ago.",
                    "Low-cost reactivation: a seasonal offer or a new-product message."),
            "HIBERNATING", new Meta("Hibernating",
                    "Repeat customers who have not ordered for over 180 days.",
                    "Win back with a strong offer; stop if there is no response."));

    /** Builds the report as of {@code asOf}. */
    public static CustomerSegmentsReport build(List<CustomerFact> customers, LocalDate asOf) {
        List<Row> rows = new ArrayList<>(customers.size());
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long repeat = 0;
        for (CustomerFact c : customers) {
            if (!Facts.isRealMobile(c.mobile()) || c.lastOrderAt() == null || c.orders() <= 0) {
                continue;
            }
            long recency = Math.max(0, ChronoUnit.DAYS.between(c.lastOrderAt().toLocalDate(), asOf));
            BigDecimal revenue = AnalyticsMath.nz(c.revenue());
            rows.add(new Row(c, recency, revenue, segmentOf(c.orders(), recency)));
            totalRevenue = totalRevenue.add(revenue);
            if (c.orders() >= 2) {
                repeat++;
            }
        }
        long n = rows.size();

        Map<String, List<Row>> bySegment = new LinkedHashMap<>();
        for (String key : SEGMENT_ORDER) {
            bySegment.put(key, new ArrayList<>());
        }
        for (Row r : rows) {
            bySegment.get(r.segment()).add(r);
        }

        List<SegmentRow> segments = new ArrayList<>();
        for (String key : SEGMENT_ORDER) {
            segments.add(segment(key, bySegment.get(key), n, totalRevenue));
        }

        long atRisk = bySegment.get("AT_RISK").size() + bySegment.get("HIBERNATING").size();
        BigDecimal atRiskRevenue = BigDecimal.ZERO;
        for (Row r : bySegment.get("AT_RISK")) {
            atRiskRevenue = atRiskRevenue.add(r.revenue());
        }
        for (Row r : bySegment.get("HIBERNATING")) {
            atRiskRevenue = atRiskRevenue.add(r.revenue());
        }

        List<Row> byValue = new ArrayList<>(rows);
        byValue.sort(Comparator.comparing(Row::revenue).reversed());

        return new CustomerSegmentsReport(
                asOf,
                n,
                AnalyticsMath.money(totalRevenue),
                AnalyticsMath.average(totalRevenue, n),
                AnalyticsMath.pct(repeat, n),
                topShare(byValue, 0.10, totalRevenue),
                topShare(byValue, 0.20, totalRevenue),
                atRisk,
                AnalyticsMath.money(atRiskRevenue),
                segments,
                frequencyBands(rows),
                recencyBands(rows),
                customerRows(byValue, TOP_CUSTOMERS));
    }

    private record Row(CustomerFact fact, long recencyDays, BigDecimal revenue, String segment) {
        long orders() {
            return fact.orders();
        }
    }

    private static SegmentRow segment(String key, List<Row> members, long totalCustomers, BigDecimal totalRevenue) {
        Meta meta = META.get(key);
        BigDecimal revenue = BigDecimal.ZERO;
        long orders = 0;
        long recency = 0;
        for (Row r : members) {
            revenue = revenue.add(r.revenue());
            orders += r.orders();
            recency += r.recencyDays();
        }
        long size = members.size();
        List<Row> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(Row::revenue).reversed());
        return new SegmentRow(
                key,
                meta.label(),
                meta.description(),
                meta.action(),
                size,
                AnalyticsMath.pct(size, totalCustomers),
                AnalyticsMath.money(revenue),
                AnalyticsMath.pct(revenue.doubleValue(), totalRevenue.doubleValue()),
                size == 0 ? 0.0 : AnalyticsMath.round1((double) orders / size),
                size == 0 ? 0.0 : AnalyticsMath.round1((double) recency / size),
                customerRows(sorted, SEGMENT_SAMPLE));
    }

    private static List<CustomerRow> customerRows(List<Row> sorted, int limit) {
        List<CustomerRow> out = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < limit; i++) {
            Row r = sorted.get(i);
            out.add(new CustomerRow(
                    r.fact().name(),
                    r.fact().mobile(),
                    r.orders(),
                    AnalyticsMath.money(r.revenue()),
                    r.fact().lastOrderAt().toLocalDate(),
                    r.recencyDays()));
        }
        return out;
    }

    /** Revenue share of the best {@code fraction} of customers (at least one customer). */
    private static double topShare(List<Row> byValueDesc, double fraction, BigDecimal totalRevenue) {
        if (byValueDesc.isEmpty() || totalRevenue.signum() <= 0) {
            return 0.0;
        }
        int take = Math.max(1, (int) Math.ceil(byValueDesc.size() * fraction));
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < take && i < byValueDesc.size(); i++) {
            sum = sum.add(byValueDesc.get(i).revenue());
        }
        return AnalyticsMath.pct(sum.doubleValue(), totalRevenue.doubleValue());
    }

    private static List<BandRow> frequencyBands(List<Row> rows) {
        String[] labels = {"1 order", "2 orders", "3-4 orders", "5+ orders"};
        long[] count = new long[4];
        BigDecimal[] revenue = zeros(4);
        for (Row r : rows) {
            long o = r.orders();
            int i = o <= 1 ? 0 : o == 2 ? 1 : o <= 4 ? 2 : 3;
            count[i]++;
            revenue[i] = revenue[i].add(r.revenue());
        }
        return bands(labels, count, revenue);
    }

    private static List<BandRow> recencyBands(List<Row> rows) {
        String[] labels = {"0-30 days", "31-60 days", "61-90 days", "91-180 days", "Over 180 days"};
        long[] count = new long[5];
        BigDecimal[] revenue = zeros(5);
        for (Row r : rows) {
            long d = r.recencyDays();
            int i = d <= 30 ? 0 : d <= 60 ? 1 : d <= 90 ? 2 : d <= 180 ? 3 : 4;
            count[i]++;
            revenue[i] = revenue[i].add(r.revenue());
        }
        return bands(labels, count, revenue);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static List<BandRow> bands(String[] labels, long[] count, BigDecimal[] revenue) {
        List<BandRow> out = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            out.add(new BandRow(labels[i], count[i], AnalyticsMath.money(revenue[i])));
        }
        return out;
    }
}
