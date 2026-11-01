package com.shifa.oms.analytics;

import com.shifa.oms.analytics.AnalyticsQueryRepository.CustomerRow;
import com.shifa.oms.analytics.AnalyticsQueryRepository.LineRow;
import com.shifa.oms.analytics.AnalyticsQueryRepository.OrderRow;
import com.shifa.oms.analytics.AnalyticsQueryRepository.StampRow;
import com.shifa.oms.analytics.domain.CustomerSegmenter;
import com.shifa.oms.analytics.domain.Facts;
import com.shifa.oms.analytics.domain.Facts.CustomerFact;
import com.shifa.oms.analytics.domain.Facts.LineFact;
import com.shifa.oms.analytics.domain.Facts.OrderFact;
import com.shifa.oms.analytics.domain.Facts.StatusStamp;
import com.shifa.oms.analytics.domain.FulfilmentEngine;
import com.shifa.oms.analytics.domain.OverviewEngine;
import com.shifa.oms.analytics.domain.PointerEngine;
import com.shifa.oms.analytics.domain.ProductEngine;
import com.shifa.oms.analytics.dto.GrowthAnalytics.CustomerSegmentsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OperationsReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.OverviewReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.PointersReport;
import com.shifa.oms.analytics.dto.GrowthAnalytics.ProductPerformanceReport;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the narrow fact rows for a period and hands them to the pure engines in
 * {@code analytics.domain}. Read-only; every date is an IST calendar date.
 */
@Service
public class GrowthAnalyticsService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** Longest period a report may span, in days. */
    static final int MAX_RANGE_DAYS = 731;
    private static final int DEFAULT_RANGE_DAYS = 30;
    /** Periods longer than this are charted by week instead of by day. */
    private static final int WEEKLY_AFTER_DAYS = 92;
    private static final int TOP_PLACES = 10;

    /** Statuses whose first timestamp the operations report needs. */
    private static final Set<OrderStatus> STAMPED = EnumSet.of(
            OrderStatus.APPROVED, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED,
            OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED,
            OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    private final AnalyticsQueryRepository queries;
    private final DeliveryPerformanceService deliveryPerformance;
    private final Clock clock;

    @Autowired
    public GrowthAnalyticsService(AnalyticsQueryRepository queries, DeliveryPerformanceService deliveryPerformance) {
        this(queries, deliveryPerformance, Clock.system(ZONE));
    }

    GrowthAnalyticsService(AnalyticsQueryRepository queries, DeliveryPerformanceService deliveryPerformance,
                           Clock clock) {
        this.queries = queries;
        this.deliveryPerformance = deliveryPerformance;
        this.clock = clock;
    }

    /** The selected period and the equally long one right before it. */
    record Period(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, long days) {
    }

    /** Resolves optional dates to a validated period; the default is the last 30 days. */
    Period period(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new ValidationException("The start date must not be after the end date.");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ValidationException("Choose a period of at most " + MAX_RANGE_DAYS + " days.");
        }
        LocalDate previousTo = start.minusDays(1);
        return new Period(start, end, previousTo.minusDays(days - 1), previousTo, days);
    }

    // --- Reports ----------------------------------------------------------

    @Transactional(readOnly = true)
    public OverviewReport overview(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        return buildOverview(p, loadOrders(p.from(), p.to()), loadOrders(p.previousFrom(), p.previousTo()),
                loadCustomers());
    }

    @Transactional(readOnly = true)
    public CustomerSegmentsReport customers() {
        return CustomerSegmenter.build(loadCustomers(), LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public ProductPerformanceReport products(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        return ProductEngine.build(p.from(), p.to(), loadLines(p.from(), p.to()),
                loadLines(p.previousFrom(), p.previousTo()));
    }

    @Transactional(readOnly = true)
    public OperationsReport operations(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        return buildOperations(p, loadOrders(p.from(), p.to()));
    }

    @Transactional(readOnly = true)
    public PointersReport pointers(LocalDate from, LocalDate to) {
        Period p = period(from, to);
        List<OrderFact> current = loadOrders(p.from(), p.to());
        List<CustomerFact> customers = loadCustomers();
        OverviewReport overview = buildOverview(p, current, loadOrders(p.previousFrom(), p.previousTo()), customers);
        ProductPerformanceReport products = ProductEngine.build(p.from(), p.to(), loadLines(p.from(), p.to()),
                loadLines(p.previousFrom(), p.previousTo()));
        return new PointersReport(p.from(), p.to(), PointerEngine.generate(
                overview,
                CustomerSegmenter.build(customers, LocalDate.now(clock)),
                products,
                buildOperations(p, current),
                deliveryPerformance.report()));
    }

    // --- Assembly ---------------------------------------------------------

    private OverviewReport buildOverview(Period p, List<OrderFact> current, List<OrderFact> previous,
                                         List<CustomerFact> customers) {
        Map<String, LocalDateTime> firstOrder = new HashMap<>();
        for (CustomerFact c : customers) {
            firstOrder.put(c.mobile(), c.firstOrderAt());
        }
        boolean weekly = p.days() > WEEKLY_AFTER_DAYS;
        return new OverviewReport(
                p.from(),
                p.to(),
                p.previousFrom(),
                p.previousTo(),
                OverviewEngine.kpis(current, firstOrder, p.from().atStartOfDay()),
                OverviewEngine.kpis(previous, firstOrder, p.previousFrom().atStartOfDay()),
                weekly ? "WEEK" : "DAY",
                OverviewEngine.trend(current, firstOrder, p.from(), p.to(), weekly),
                OverviewEngine.mix(current, o -> OverviewEngine.channelKey(o.source()), OverviewEngine::channelLabel),
                OverviewEngine.mix(current,
                        o -> o.leadSource() == null ? OverviewEngine.NOT_RECORDED : o.leadSource().name(),
                        OverviewEngine::leadSourceLabel),
                OverviewEngine.mix(current,
                        o -> o.payment() == null ? "UNKNOWN" : o.payment().name(),
                        key -> "UNKNOWN".equals(key) ? "Unknown"
                                : OverviewEngine.paymentLabel(com.shifa.oms.order.domain.PaymentStatus.valueOf(key))),
                OverviewEngine.geo(current, OverviewEngine::stateOf, TOP_PLACES),
                OverviewEngine.geo(current, OverviewEngine::cityOf, TOP_PLACES),
                OverviewEngine.heatmap(current));
    }

    private OperationsReport buildOperations(Period p, List<OrderFact> orders) {
        List<StatusStamp> stamps = new ArrayList<>();
        for (StampRow r : queries.stamps(p.from().atStartOfDay(), p.to().plusDays(1).atStartOfDay(), STAMPED)) {
            if (r.getOrderId() != null && r.getToStatus() != null && r.getChangedAt() != null) {
                stamps.add(new StatusStamp(r.getOrderId(), r.getToStatus(), r.getChangedAt()));
            }
        }
        return FulfilmentEngine.build(p.from(), p.to(), orders, stamps);
    }

    // --- Loading ----------------------------------------------------------

    /** Orders placed on the IST dates {@code from..to} inclusive. */
    public List<OrderFact> loadOrders(LocalDate from, LocalDate to) {
        List<OrderFact> facts = new ArrayList<>();
        for (OrderRow r : queries.orders(from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            facts.add(new OrderFact(
                    r.getId(), r.getCode(), r.getCreatedAt(), r.getMobile(), r.getCustomerName(),
                    r.getState(), r.getCity(), r.getPostalCode(), r.getCountry(),
                    r.getOrderStatus(), r.getPaymentStatus(), r.getOrderSource(), r.getLeadSource(),
                    r.getDeliveryMethod(), r.getTotal(), r.getDiscount(), r.getReceived(), r.getCod(),
                    r.getCreatedBy(), r.getRejectReason(), r.getRtoReason()));
        }
        return facts;
    }

    /** Order lines of the orders placed on the IST dates {@code from..to} inclusive. */
    public List<LineFact> loadLines(LocalDate from, LocalDate to) {
        List<LineFact> facts = new ArrayList<>();
        for (LineRow r : queries.lines(from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
            facts.add(new LineFact(
                    r.getOrderId(), r.getOrderCode(), r.getCreatedAt(), r.getMobile(), r.getOrderStatus(),
                    r.getProductId(), r.getProductName(), r.getQuantity() == null ? 0 : r.getQuantity(),
                    r.getRate(), r.getLineTotal()));
        }
        return facts;
    }

    /** Today's IST date by this service's clock. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Every real customer with their sales summed over all time. */
    public List<CustomerFact> loadCustomers() {
        List<CustomerFact> facts = new ArrayList<>();
        for (CustomerRow r : queries.customers(Facts.LOST_STATUSES, Facts.PLACEHOLDER_MOBILE)) {
            if (r.getMobile() == null || r.getFirstOrderAt() == null || r.getLastOrderAt() == null) {
                continue;
            }
            facts.add(new CustomerFact(
                    r.getMobile(), r.getCustomerName(), r.getOrders() == null ? 0 : r.getOrders(),
                    r.getRevenue(), r.getFirstOrderAt(), r.getLastOrderAt()));
        }
        return facts;
    }
}
