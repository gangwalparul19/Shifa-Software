package com.shifa.oms.analytics.domain;

import com.shifa.oms.analytics.dto.DeliveryPerformanceReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.AffinityPair;
import com.shifa.oms.analytics.dto.GrowthAnalytics.CustomerSegmentsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.Kpis;
import com.shifa.oms.analytics.dto.GrowthAnalytics.MixRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OperationsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OverviewReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.PaymentRiskRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.Pointer;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductPerformanceReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ReasonRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.SegmentRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.SpeedStat;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the other reports into a short, ranked list of things worth acting on. Every
 * rule is explicit, needs a minimum amount of data before it speaks, and points at the
 * screen where the team can act. Nothing here is a prediction; each pointer restates
 * a figure from the reports.
 */
public final class PointerEngine {

    public static final String WARNING = "WARNING";
    public static final String OPPORTUNITY = "OPPORTUNITY";
    public static final String POSITIVE = "POSITIVE";
    public static final String INFO = "INFO";

    /** Pointers returned at most. */
    public static final int MAX_POINTERS = 12;

    private static final Map<String, Integer> RANK = Map.of(WARNING, 0, OPPORTUNITY, 1, POSITIVE, 2, INFO, 3);

    private PointerEngine() {
    }

    /** The pointers for a period; delivery performance and the other reports are optional inputs. */
    public static List<Pointer> generate(OverviewReport overview, CustomerSegmentsReport customers,
                                         ProductPerformanceReport products, OperationsReport ops,
                                         DeliveryPerformanceReport delivery) {
        List<Pointer> out = new ArrayList<>();
        Kpis cur = overview.current();
        Kpis prev = overview.previous();

        if (cur.orders() == 0) {
            out.add(new Pointer("NO_ORDERS", INFO, "Sales", "No orders in this period",
                    "Pick a longer period to see trends, or check that orders are being entered.",
                    "0 orders", "Open orders", "/orders"));
        } else {
            salesTrend(out, cur, prev);
            customerPointers(out, overview, customers);
            paymentPointers(out, ops);
            deliveryPointers(out, delivery);
            peakTime(out, overview);
            leadSources(out, overview);
            productPointers(out, products);
            operationPointers(out, ops);
            lostOrders(out, cur, ops);
            discounting(out, cur);
        }
        out.sort(Comparator.comparingInt(p -> RANK.getOrDefault(p.severity(), 9)));
        return out.size() > MAX_POINTERS ? new ArrayList<>(out.subList(0, MAX_POINTERS)) : out;
    }

    // --- Rules -----------------------------------------------------------

    private static void salesTrend(List<Pointer> out, Kpis cur, Kpis prev) {
        if (prev.revenue().signum() > 0) {
            double change = (cur.revenue().doubleValue() - prev.revenue().doubleValue()) * 100.0
                    / prev.revenue().doubleValue();
            if (change >= 10) {
                out.add(new Pointer("REVENUE_UP", POSITIVE, "Sales",
                        "Revenue is up " + Math.round(change) + "% on the previous period",
                        "Sales of " + inr(cur.revenue()) + " against " + inr(prev.revenue())
                                + ". Find what drove it (a product, a source, a salesperson) and repeat it.",
                        "+" + Math.round(change) + "%", "See the sales trend", "/analytics?tab=overview"));
            } else if (change <= -10) {
                out.add(new Pointer("REVENUE_DOWN", WARNING, "Sales",
                        "Revenue is down " + Math.round(-change) + "% on the previous period",
                        "Sales of " + inr(cur.revenue()) + " against " + inr(prev.revenue())
                                + ". Check whether orders, order value or new customers fell.",
                        Math.round(change) + "%", "See the sales trend", "/analytics?tab=overview"));
            }
        }
        if (prev.newCustomers() >= 5 && cur.newCustomers() * 100 <= prev.newCustomers() * 80) {
            out.add(new Pointer("ACQUISITION_SLOWING", WARNING, "Customers",
                    "New customers are slowing",
                    cur.newCustomers() + " new customers against " + prev.newCustomers()
                            + " in the previous period. Review lead sources and ad spend.",
                    cur.newCustomers() + " vs " + prev.newCustomers(), "See lead sources", "/analytics?tab=acquisition"));
        }
    }

    private static void customerPointers(List<Pointer> out, OverviewReport overview, CustomerSegmentsReport c) {
        if (c.totalCustomers() < 10) {
            return;
        }
        if (c.atRiskCustomers() >= 5) {
            out.add(new Pointer("WIN_BACK", OPPORTUNITY, "Customers",
                    c.atRiskCustomers() + " repeat customers have gone quiet",
                    "They have bought before and spent " + inr(c.atRiskRevenue())
                            + " in total, but have not ordered for 61+ days. A personal message with a reorder offer is the cheapest sale available.",
                    inr(c.atRiskRevenue()) + " lifetime value", "See who to contact", "/analytics?tab=customers"));
        }
        SegmentRow attention = segment(c, "NEEDS_ATTENTION");
        if (attention != null && attention.customers() >= 10) {
            out.add(new Pointer("SECOND_ORDER", OPPORTUNITY, "Customers",
                    attention.customers() + " customers have ordered once and are cooling off",
                    "Their only order was 31-90 days ago. Ask how they are finding the product and offer a second order now.",
                    attention.customers() + " customers", "See the list", "/analytics?tab=customers"));
        }
        if (c.repeatRatePct() < 25) {
            out.add(new Pointer("LOW_REPEAT", OPPORTUNITY, "Customers",
                    "Only " + c.repeatRatePct() + "% of customers have ordered twice",
                    "Reorders cost far less than new customers. Add a reorder reminder after the usual course length and offer a bundle on the first order.",
                    c.repeatRatePct() + "% repeat", "See retention", "/analytics?tab=retention"));
        }
        if (c.top10PctRevenueSharePct() >= 50) {
            out.add(new Pointer("CONCENTRATION", WARNING, "Customers",
                    "The top 10% of customers bring " + c.top10PctRevenueSharePct() + "% of revenue",
                    "Losing a few of them would hurt. Look after them personally.",
                    c.top10PctRevenueSharePct() + "%", "See top customers", "/analytics?tab=customers"));
        }
        Kpis cur = overview.current();
        if (cur.customers() >= 20 && cur.returningRevenueSharePct() >= 50) {
            out.add(new Pointer("RETURNING_STRONG", POSITIVE, "Customers",
                    "Returning customers bring " + cur.returningRevenueSharePct() + "% of this period's revenue",
                    "Your base is loyal. Protect it with reorder reminders and early access to new products.",
                    cur.returningRevenueSharePct() + "%", null, null));
        }
    }

    private static void paymentPointers(List<Pointer> out, OperationsReport ops) {
        PaymentRiskRow prepaid = payment(ops, "FULLY_PAID");
        PaymentRiskRow cod = payment(ops, "COD");
        if (prepaid == null || cod == null) {
            return;
        }
        long prepaidOutcomes = prepaid.delivered() + prepaid.failed();
        long codOutcomes = cod.delivered() + cod.failed();
        if (prepaidOutcomes >= 10 && codOutcomes >= 10
                && prepaid.successRatePct() - cod.successRatePct() >= 10) {
            out.add(new Pointer("COD_RISK", WARNING, "Operations",
                    "Pay-on-delivery orders fail more often",
                    "Pay-on-delivery orders are delivered " + cod.successRatePct() + "% of the time against "
                            + prepaid.successRatePct() + "% for prepaid. Ask for a part payment up front on risky pincodes.",
                    cod.successRatePct() + "% vs " + prepaid.successRatePct() + "%",
                    "See delivery by area", "/analytics?tab=delivery"));
        }
    }

    private static void deliveryPointers(List<Pointer> out, DeliveryPerformanceReport delivery) {
        if (delivery == null) {
            return;
        }
        DeliveryPerformanceReport.Row worst = null;
        for (DeliveryPerformanceReport.Row r : delivery.byState()) {
            if (r.total() >= 10 && (worst == null || r.successRate() < worst.successRate())) {
                worst = r;
            }
        }
        if (worst != null && worst.successRate() < 75) {
            out.add(new Pointer("WORST_STATE", WARNING, "Operations",
                    "Deliveries to " + worst.dimension() + " succeed only " + worst.successRate() + "%",
                    worst.failed() + " of " + worst.total() + " orders there were refused, failed or returned (all time). Confirm the address and phone by call before dispatch, or take part payment first.",
                    worst.successRate() + "% delivered", "See delivery by area", "/analytics?tab=delivery"));
        }
    }

    private static void peakTime(List<Pointer> out, OverviewReport overview) {
        var heat = overview.heatmap();
        long total = heat.byWeekday().stream().mapToLong(Long::longValue).sum();
        if (total < 30 || heat.peakDay() < 0 || heat.peakHour() < 0) {
            return;
        }
        String day = DayOfWeek.of(heat.peakDay() + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        out.add(new Pointer("PEAK_TIME", INFO, "Sales",
                "Orders peak on " + day + "s around " + hourLabel(heat.peakHour()),
                "Send WhatsApp offers and follow-ups an hour or two before this window, and make sure the sales team is covered then.",
                day + ", " + hourLabel(heat.peakHour()), "See the heat map", "/analytics?tab=overview"));
    }

    private static void leadSources(List<Pointer> out, OverviewReport overview) {
        List<MixRow> sources = overview.leadSourceMix();
        long total = sources.stream().mapToLong(MixRow::orders).sum();
        if (total < 20) {
            return;
        }
        MixRow best = null;
        for (MixRow r : sources) {
            if (!OverviewEngine.NOT_RECORDED.equals(r.key()) && (best == null || r.revenue().compareTo(best.revenue()) > 0)) {
                best = r;
            }
        }
        if (best != null) {
            out.add(new Pointer("TOP_SOURCE", INFO, "Acquisition",
                    best.label() + " is the biggest known source of revenue",
                    best.orders() + " orders and " + inr(best.revenue()) + " (" + best.revenueSharePct()
                            + "% of revenue), average order " + inr(best.avgOrderValue()) + ".",
                    best.revenueSharePct() + "% of revenue", "See sources", "/analytics?tab=acquisition"));
        }
        for (MixRow r : sources) {
            if (OverviewEngine.NOT_RECORDED.equals(r.key()) && r.orders() * 100 >= total * 20) {
                out.add(new Pointer("SOURCE_GAP", INFO, "Acquisition",
                        AnalyticsMath.pct(r.orders(), total) + "% of orders have no lead source",
                        "Without a source you cannot tell which channel pays back. Ask the team to pick one on every order.",
                        r.orders() + " orders", null, null));
            }
        }
    }

    private static void productPointers(List<Pointer> out, ProductPerformanceReport products) {
        if (products.productsSold() == 0) {
            return;
        }
        ProductRow riser = null;
        ProductRow faller = null;
        for (ProductRow p : products.products()) {
            if (p.revenueGrowthPct() == null || p.previousUnits() < 10) {
                continue;
            }
            if (p.revenueGrowthPct() >= 50 && (riser == null || p.revenueGrowthPct() > riser.revenueGrowthPct())) {
                riser = p;
            }
            if (p.revenueGrowthPct() <= -30 && (faller == null || p.revenueGrowthPct() < faller.revenueGrowthPct())) {
                faller = p;
            }
        }
        if (riser != null) {
            out.add(new Pointer("PRODUCT_RISING", POSITIVE, "Products",
                    riser.name() + " is up " + Math.round(riser.revenueGrowthPct()) + "%",
                    "Revenue of " + inr(riser.revenue()) + " against " + inr(riser.previousRevenue())
                            + ". Check stock cover and feature it in your next message.",
                    "+" + Math.round(riser.revenueGrowthPct()) + "%", "See products", "/analytics?tab=products"));
        }
        if (faller != null) {
            out.add(new Pointer("PRODUCT_FALLING", WARNING, "Products",
                    faller.name() + " is down " + Math.round(-faller.revenueGrowthPct()) + "%",
                    "Revenue of " + inr(faller.revenue()) + " against " + inr(faller.previousRevenue())
                            + ". Look at price, stock-outs and reviews.",
                    Math.round(faller.revenueGrowthPct()) + "%", "See products", "/analytics?tab=products"));
        }
        if (!products.pairs().isEmpty()) {
            AffinityPair pair = products.pairs().get(0);
            if (pair.orders() >= 3 && pair.lift() >= 1.2) {
                out.add(new Pointer("BUNDLE", OPPORTUNITY, "Products",
                        pair.productA() + " and " + pair.productB() + " sell together",
                        "Bought in the same order " + pair.orders() + " times, " + pair.lift()
                                + "x more often than chance. Offer them as a bundle or suggest one when the other is added.",
                        pair.orders() + " orders", "See pairs", "/analytics?tab=products"));
            }
        }
        if (products.productsSold() >= 4 && products.top3RevenueSharePct() >= 70) {
            out.add(new Pointer("HERO_PRODUCTS", INFO, "Products",
                    "Three products make " + products.top3RevenueSharePct() + "% of revenue",
                    "Keep them in stock first, and use them as the entry point for cross-selling the rest of the range.",
                    products.top3RevenueSharePct() + "%", "See products", "/analytics?tab=products"));
        }
    }

    private static void operationPointers(List<Pointer> out, OperationsReport ops) {
        SpeedStat approval = speed(ops, "APPROVAL");
        if (approval != null && approval.samples() >= 10 && approval.medianHours() != null && approval.medianHours() > 4) {
            out.add(new Pointer("SLOW_APPROVAL", WARNING, "Operations",
                    "Orders wait about " + approval.medianHours() + " hours for approval",
                    "Half of orders wait that long before moving; the slowest tenth wait " + approval.p90Hours()
                            + " hours. Faster approval means faster delivery and fewer cancellations.",
                    approval.medianHours() + " h median", "Open approval queue", "/approval-queue"));
        }
        SpeedStat total = speed(ops, "END_TO_END");
        if (total != null && total.samples() >= 10 && total.medianHours() != null && total.medianHours() > 120) {
            out.add(new Pointer("SLOW_DELIVERY", WARNING, "Operations",
                    "Delivery takes about " + Math.round(total.medianHours() / 24) + " days end to end",
                    "From order to doorstep the median is " + total.medianHours() + " hours. Slow delivery lowers repeat orders; check the courier and dispatch speed.",
                    total.medianHours() + " h median", "See delivery by area", "/analytics?tab=delivery"));
        }
    }

    private static void lostOrders(List<Pointer> out, Kpis cur, OperationsReport ops) {
        if (cur.lostOrders() < 5 || cur.lostOrdersPct() < 10) {
            return;
        }
        ReasonRow top = ops.rejectReasons().isEmpty() ? null : ops.rejectReasons().get(0);
        String reason = top == null ? "" : " The most common recorded reason is \"" + top.label().toLowerCase(Locale.ENGLISH) + "\".";
        out.add(new Pointer("LOST_ORDERS", WARNING, "Operations",
                cur.lostOrdersPct() + "% of orders were rejected or cancelled",
                cur.lostOrders() + " orders did not become sales." + reason + " Fix the cause at order entry.",
                cur.lostOrders() + " orders", "See lost orders", "/analytics?tab=operations"));
    }

    private static void discounting(List<Pointer> out, Kpis cur) {
        if (cur.orders() >= 20 && cur.discountedOrdersPct() >= 40) {
            out.add(new Pointer("DISCOUNTING", INFO, "Sales",
                    cur.discountedOrdersPct() + "% of orders carry a discount",
                    inr(cur.discountTotal()) + " given away in total. If discounts are the default, customers will wait for them.",
                    inr(cur.discountTotal()), null, null));
        }
    }

    // --- Helpers ---------------------------------------------------------

    private static SegmentRow segment(CustomerSegmentsReport c, String key) {
        for (SegmentRow s : c.segments()) {
            if (key.equals(s.key())) {
                return s;
            }
        }
        return null;
    }

    private static PaymentRiskRow payment(OperationsReport ops, String key) {
        for (PaymentRiskRow r : ops.paymentRisk()) {
            if (key.equals(r.key())) {
                return r;
            }
        }
        return null;
    }

    private static SpeedStat speed(OperationsReport ops, String key) {
        for (SpeedStat s : ops.speed()) {
            if (key.equals(s.key())) {
                return s;
            }
        }
        return null;
    }

    private static String hourLabel(int hour) {
        int next = (hour + 1) % 24;
        return String.format(Locale.ENGLISH, "%02d:00-%02d:00", hour, next);
    }

    /** Whole rupees with Indian digit grouping (a new formatter each time: NumberFormat is not thread-safe). */
    private static String inr(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.of("en", "IN"));
        return "\u20B9" + format.format(value == null ? BigDecimal.ZERO : value);
    }
}
