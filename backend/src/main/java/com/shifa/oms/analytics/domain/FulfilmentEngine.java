package com.shifa.oms.analytics.domain;

import com.shifa.oms.analytics.domain.Facts.OrderFact;
import com.shifa.oms.analytics.domain.Facts.StatusStamp;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OperationsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OutcomeRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.PaymentRiskRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ReasonRow;
import com.shifa.oms.analytics.dto.GrowthAnalytics.SpeedStat;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure calculations behind the Operations tab: how long orders take at each step
 * (from the first time each status was reached), how they end, how delivery success
 * differs by how the customer paid, and why orders are rejected or returned.
 */
public final class FulfilmentEngine {

    private FulfilmentEngine() {
    }

    /** Builds the report for the orders placed in the period and their status stamps. */
    public static OperationsReport build(LocalDate from, LocalDate to, List<OrderFact> orders,
                                         List<StatusStamp> stamps) {
        Map<Long, Map<OrderStatus, LocalDateTime>> byOrder = new HashMap<>();
        for (StatusStamp s : stamps) {
            byOrder.computeIfAbsent(s.orderId(), k -> new EnumMap<>(OrderStatus.class)).put(s.status(), s.at());
        }
        return new OperationsReport(
                from,
                to,
                orders.size(),
                speed(orders, byOrder),
                outcomes(orders),
                paymentRisk(orders),
                rejectReasons(orders),
                rtoReasons(orders));
    }

    private static LocalDateTime firstOf(Map<OrderStatus, LocalDateTime> stamps, OrderStatus... statuses) {
        LocalDateTime best = null;
        if (stamps == null) {
            return null;
        }
        for (OrderStatus s : statuses) {
            LocalDateTime at = stamps.get(s);
            if (at != null && (best == null || at.isBefore(best))) {
                best = at;
            }
        }
        return best;
    }

    private static List<SpeedStat> speed(List<OrderFact> orders, Map<Long, Map<OrderStatus, LocalDateTime>> byOrder) {
        List<Double> approval = new ArrayList<>();
        List<Double> dispatch = new ArrayList<>();
        List<Double> delivery = new ArrayList<>();
        List<Double> endToEnd = new ArrayList<>();
        for (OrderFact o : orders) {
            if (o.createdAt() == null || o.isLost()) {
                continue;
            }
            Map<OrderStatus, LocalDateTime> s = byOrder.get(o.id());
            LocalDateTime approved = firstOf(s, OrderStatus.APPROVED);
            LocalDateTime shipped = firstOf(s, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED,
                    OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT);
            LocalDateTime delivered = firstOf(s, OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);
            add(approval, o.createdAt(), approved);
            add(dispatch, approved, shipped);
            add(delivery, shipped, delivered);
            add(endToEnd, o.createdAt(), delivered);
        }
        return List.of(
                stat("APPROVAL", "Order placed to approved", approval),
                stat("DISPATCH", "Approved to handed over", dispatch),
                stat("DELIVERY", "Handed over to delivered", delivery),
                stat("END_TO_END", "Order placed to delivered", endToEnd));
    }

    /** Records the gap in hours when both ends are known and the gap is not negative. */
    private static void add(List<Double> sink, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            return;
        }
        sink.add(Duration.between(start, end).toMinutes() / 60.0);
    }

    private static SpeedStat stat(String key, String label, List<Double> hours) {
        if (hours.isEmpty()) {
            return new SpeedStat(key, label, 0, null, null, null);
        }
        List<Double> sorted = new ArrayList<>(hours);
        Collections.sort(sorted);
        double sum = 0;
        for (double h : sorted) {
            sum += h;
        }
        return new SpeedStat(
                key,
                label,
                sorted.size(),
                AnalyticsMath.round1(AnalyticsMath.median(sorted)),
                AnalyticsMath.round1(AnalyticsMath.percentile(sorted, 90)),
                AnalyticsMath.round1(sum / sorted.size()));
    }

    private static String groupLabel(OrderStatusGroup g) {
        return switch (g) {
            case PENDING_APPROVAL -> "Pending approval";
            case PROCESSING -> "Processing";
            case SHIPPED -> "Shipped";
            case DELIVERED -> "Delivered";
            case FAILED_RETURNED -> "Failed / returned";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Rejected";
        };
    }

    private static List<OutcomeRow> outcomes(List<OrderFact> orders) {
        Map<OrderStatusGroup, Long> counts = new EnumMap<>(OrderStatusGroup.class);
        for (OrderFact o : orders) {
            OrderStatusGroup g = OrderStatusGroup.groupOf(o.status());
            if (g != null) {
                counts.merge(g, 1L, Long::sum);
            }
        }
        List<OutcomeRow> rows = new ArrayList<>();
        for (OrderStatusGroup g : OrderStatusGroup.values()) {
            long n = counts.getOrDefault(g, 0L);
            rows.add(new OutcomeRow(g.name(), groupLabel(g), n, AnalyticsMath.pct(n, orders.size())));
        }
        return rows;
    }

    private static List<PaymentRiskRow> paymentRisk(List<OrderFact> orders) {
        Map<PaymentStatus, long[]> counts = new EnumMap<>(PaymentStatus.class);
        Map<PaymentStatus, BigDecimal> revenue = new EnumMap<>(PaymentStatus.class);
        for (OrderFact o : orders) {
            if (!o.isSale() || o.payment() == null) {
                continue;
            }
            long[] c = counts.computeIfAbsent(o.payment(), k -> new long[3]);
            c[0]++;
            if (o.isDelivered()) {
                c[1]++;
            } else if (o.isFailed()) {
                c[2]++;
            }
            revenue.merge(o.payment(), AnalyticsMath.nz(o.total()), BigDecimal::add);
        }
        List<PaymentRiskRow> rows = new ArrayList<>();
        for (PaymentStatus p : new PaymentStatus[]{PaymentStatus.FULLY_PAID, PaymentStatus.PARTIALLY_PAID, PaymentStatus.COD}) {
            long[] c = counts.getOrDefault(p, new long[3]);
            rows.add(new PaymentRiskRow(
                    p.name(),
                    OverviewEngine.paymentLabel(p),
                    c[0],
                    c[1],
                    c[2],
                    AnalyticsMath.pct(c[1], c[1] + c[2]),
                    AnalyticsMath.money(revenue.get(p))));
        }
        return rows;
    }

    private static List<ReasonRow> rejectReasons(List<OrderFact> orders) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OrderFact o : orders) {
            if (o.status() == OrderStatus.REJECTED || o.status() == OrderStatus.PAYMENT_REJECTED) {
                String key = o.rejectReason() == null ? "UNSPECIFIED" : o.rejectReason().name();
                counts.merge(key, 1L, Long::sum);
            }
        }
        return reasons(counts);
    }

    private static List<ReasonRow> rtoReasons(List<OrderFact> orders) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OrderFact o : orders) {
            if (o.rtoReason() != null) {
                counts.merge(o.rtoReason().name(), 1L, Long::sum);
            }
        }
        return reasons(counts);
    }

    private static List<ReasonRow> reasons(Map<String, Long> counts) {
        List<ReasonRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            rows.add(new ReasonRow(e.getKey(), AnalyticsMath.humanize(e.getKey()), e.getValue()));
        }
        rows.sort((a, b) -> Long.compare(b.orders(), a.orders()));
        return rows;
    }
}
