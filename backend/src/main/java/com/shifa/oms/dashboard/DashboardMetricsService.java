package com.shifa.oms.dashboard;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.ActivityCards;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse.MetricCards;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse.SalesGraph;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse.SalesPoint;
import com.shifa.oms.dashboard.dto.DashboardMetricsResponse.TopPerformers;
import com.shifa.oms.dashboard.dto.LiveStats;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.PercentChange;
import com.shifa.oms.reporting.domain.ReportAggregator;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the admin dashboard's aggregated metrics, live stats, and activity
 * counts (Req 19.1&ndash;19.7).
 *
 * <p>It loads every order (the dashboard is admin-only, so it is never
 * salesperson-scoped) as pure {@link OrderReportRecord}s and delegates all
 * windowing, previous-period comparison, and top-performer selection to the
 * pure {@link ReportAggregator} / {@link DateRange} / {@link PercentChange}
 * already validated by the reporting module (Property 23). Only the metric-card
 * status breakdown, the sales-graph bucketing, and the receivables-derived
 * totals are computed here, on top of that shared core.
 */
@Service
public class DashboardMetricsService {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Statuses treated as "delivered" for the delivered card and conversion rate. */
    private static final Set<OrderStatus> DELIVERED_STATES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    /** Statuses treated as "in dispatch" for the dispatched card. */
    private static final Set<OrderStatus> DISPATCHED_STATES =
            EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    /** Statuses treated as "to fulfill" for the activity card. */
    private static final Set<OrderStatus> TO_FULFILL_STATES =
            EnumSet.of(OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED,
                    OrderStatus.PACKED, OrderStatus.COURIER_ASSIGNED);

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final UserRepository userRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReportAggregator aggregator = new ReportAggregator();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DashboardMetricsService(OrderRepository orderRepository,
                                   ReceivableRepository receivableRepository,
                                   UserRepository userRepository,
                                   OutboxEventRepository outboxEventRepository) {
        this(orderRepository, receivableRepository, userRepository, outboxEventRepository,
                Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    DashboardMetricsService(OrderRepository orderRepository,
                            ReceivableRepository receivableRepository,
                            UserRepository userRepository,
                            OutboxEventRepository outboxEventRepository,
                            Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.userRepository = userRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
    }

    /**
     * Computes the full dashboard metrics for a period (Req 19.1&ndash;19.4, 19.7).
     *
     * @param period the selected preset (or {@link MetricsPeriod#CUSTOM})
     * @param bucket the sales-graph granularity, or {@code null} to use the
     *               period's natural default
     * @param from   custom-range start (used only for {@link MetricsPeriod#CUSTOM})
     * @param to     custom-range end (used only for {@link MetricsPeriod#CUSTOM})
     */
    @Transactional(readOnly = true)
    public DashboardMetricsResponse metrics(MetricsPeriod period, SalesBucket bucket,
                                            LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        DateRange window = period.resolve(today, from, to);
        SalesBucket effectiveBucket = bucket != null ? bucket : period.defaultBucket();

        List<OrderReportRecord> records = loadRecords();
        List<OrderReportRecord> windowed = aggregator.within(records, window);

        MetricCards cards = buildCards(records, windowed, window);
        SalesGraph salesGraph = buildSalesGraph(records, window, effectiveBucket);
        TopPerformers performers = buildTopPerformers(records, window);

        return new DashboardMetricsResponse(
                period.name(), effectiveBucket.name(), window.from(), window.to(),
                cards, salesGraph, performers);
    }

    /** Real-time headline stats (Req 19.5), also pushed periodically over SSE. */
    @Transactional(readOnly = true)
    public LiveStats liveStats() {
        LocalDate today = LocalDate.now(clock);
        List<OrderEntity> orders = orderRepository.findAll();
        long realtimeOrders = 0;
        BigDecimal todaysCollection = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            if (o.getCreatedAt() != null && o.getCreatedAt().toLocalDate().equals(today)) {
                realtimeOrders++;
                todaysCollection = todaysCollection.add(nz(o.getAmountReceived()));
            }
        }
        return new LiveStats(
                realtimeOrders,
                scale(todaysCollection),
                unsettledTotal(ReceivableType.COD_RECEIVABLE),
                unsettledTotal(ReceivableType.CLAIM_RECEIVABLE));
    }

    /** Activity-card counts (Req 19.6), also pushed periodically over SSE. */
    @Transactional(readOnly = true)
    public ActivityCards activityCards() {
        List<OrderEntity> orders = orderRepository.findAll();
        long toFulfill = 0;
        long toCapture = 0;
        long rto = 0;
        for (OrderEntity o : orders) {
            OrderStatus status = o.getOrderStatus();
            if (TO_FULFILL_STATES.contains(status)) {
                toFulfill++;
            }
            if (status == OrderStatus.PENDING_ADMIN_APPROVAL) {
                toCapture++;
            }
            if (status == OrderStatus.RTO) {
                rto++;
            }
        }
        long whatsappSent = countSentWhatsapp();
        long codPending = countUnsettled(ReceivableType.COD_RECEIVABLE);
        long claimsPending = countUnsettled(ReceivableType.CLAIM_RECEIVABLE);
        return new ActivityCards(toFulfill, toCapture, rto, whatsappSent, codPending, claimsPending);
    }

    // --- Metric cards -------------------------------------------------------

    private MetricCards buildCards(List<OrderReportRecord> all,
                                   List<OrderReportRecord> windowed, DateRange window) {
        long total = windowed.size();
        long pending = 0;
        long packed = 0;
        long dispatched = 0;
        long delivered = 0;
        long rto = 0;
        long lost = 0;
        for (OrderReportRecord r : windowed) {
            OrderStatus s = r.orderStatus();
            if (s == OrderStatus.PENDING_ADMIN_APPROVAL) {
                pending++;
            } else if (s == OrderStatus.PACKED) {
                packed++;
            } else if (DISPATCHED_STATES.contains(s)) {
                dispatched++;
            } else if (DELIVERED_STATES.contains(s)) {
                delivered++;
            } else if (s == OrderStatus.RTO) {
                rto++;
            } else if (s == OrderStatus.COURIER_LOST) {
                lost++;
            }
        }
        BigDecimal totalSales = aggregator.totalSales(all, window);
        BigDecimal conversionRate = total == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(delivered)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new MetricCards(
                totalSales, total, pending, packed, dispatched, delivered, rto, lost,
                unsettledTotal(ReceivableType.COD_RECEIVABLE),
                unsettledTotal(ReceivableType.CLAIM_RECEIVABLE),
                conversionRate);
    }

    // --- Sales graph --------------------------------------------------------

    private SalesGraph buildSalesGraph(List<OrderReportRecord> records, DateRange window,
                                       SalesBucket bucket) {
        List<Bucket> current = buckets(window, bucket);
        fillSales(current, records);

        DateRange previous = window.previousPeriod();
        List<Bucket> prior = previous != null ? buckets(previous, bucket) : List.of();
        if (previous != null) {
            fillSales(prior, records);
        }

        List<SalesPoint> points = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            BigDecimal prevSales = i < prior.size() ? prior.get(i).sales : BigDecimal.ZERO;
            points.add(new SalesPoint(current.get(i).label, scale(current.get(i).sales),
                    scale(prevSales)));
        }

        PercentChange change = aggregator.salesPercentChange(records, window);
        return new SalesGraph(points, change.applicable(), change.value());
    }

    /** Builds the ordered, empty buckets that tile the (bounded) window. */
    private List<Bucket> buckets(DateRange window, SalesBucket bucket) {
        LocalDate from = window.from();
        LocalDate to = window.to();
        List<Bucket> result = new ArrayList<>();
        if (from == null || to == null || from.isAfter(to)) {
            return result;
        }
        switch (bucket) {
            case DAY -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    result.add(new Bucket(d, d, d.format(DAY_LABEL)));
                }
            }
            case WEEK -> {
                LocalDate weekStart = MetricsPeriod.startOfWeek(from);
                while (!weekStart.isAfter(to)) {
                    LocalDate weekEnd = weekStart.plusDays(6);
                    result.add(new Bucket(weekStart, weekEnd, "Wk " + weekStart.format(DAY_LABEL)));
                    weekStart = weekStart.plusWeeks(1);
                }
            }
            case MONTH -> {
                YearMonth ym = YearMonth.from(from);
                YearMonth end = YearMonth.from(to);
                while (!ym.isAfter(end)) {
                    result.add(new Bucket(ym.atDay(1), ym.atEndOfMonth(), ym.toString()));
                    ym = ym.plusMonths(1);
                }
            }
        }
        return result;
    }

    /** Adds each windowed order's sales into the bucket whose range contains its date. */
    private void fillSales(List<Bucket> buckets, List<OrderReportRecord> records) {
        if (buckets.isEmpty()) {
            return;
        }
        for (OrderReportRecord r : records) {
            LocalDate date = r.orderDate();
            if (date == null) {
                continue;
            }
            for (Bucket b : buckets) {
                if (!date.isBefore(b.start) && !date.isAfter(b.end)) {
                    b.sales = b.sales.add(nz(r.totalAmount()));
                    break;
                }
            }
        }
    }

    // --- Top performers -----------------------------------------------------

    private TopPerformers buildTopPerformers(List<OrderReportRecord> records, DateRange window) {
        Long topSalespersonId = aggregator.topSalesperson(records, window).orElse(null);
        String topSalespersonName = topSalespersonId == null ? null
                : userRepository.findById(topSalespersonId).map(User::getFullName).orElse(null);
        String topProduct = aggregator.topProduct(records, window).orElse(null);
        String topState = aggregator.topState(records, window).orElse(null);
        return new TopPerformers(topSalespersonId, topSalespersonName, topProduct, topState);
    }

    // --- Loading & helpers --------------------------------------------------

    /** Loads every order as a pure report record (admin dashboard is unscoped). */
    private List<OrderReportRecord> loadRecords() {
        List<OrderEntity> orders = orderRepository.findAll();
        List<OrderReportRecord> records = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            records.add(toRecord(o));
        }
        return records;
    }

    private OrderReportRecord toRecord(OrderEntity o) {
        List<OrderReportRecord.ProductLine> products = new ArrayList<>();
        for (OrderLineItem line : o.getLineItems()) {
            products.add(new OrderReportRecord.ProductLine(
                    line.getProductName(), line.getQuantity(), line.getRate(), line.getLineTotal()));
        }
        LocalDate orderDate = o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : null;
        return new OrderReportRecord(
                o.getId(), o.getOrderCode(), orderDate, o.getCreatedBy(),
                o.getCustomerName(), o.getCustomerMobile(), o.getState(), products,
                o.getTotalAmount(), o.getAmountReceived(), o.getCodAmount(),
                o.getPaymentStatus(), o.getOrderStatus(), null, null, null);
    }

    private BigDecimal unsettledTotal(ReceivableType type) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceivableEntity e : receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(type)) {
            total = total.add(nz(e.getAmount()));
        }
        return scale(total);
    }

    private long countUnsettled(ReceivableType type) {
        return receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(type).size();
    }

    /** WhatsApp messages successfully delivered (SENT {@code WHATSAPP_NOTIFY} outbox rows, Req 19.6). */
    private long countSentWhatsapp() {
        return outboxEventRepository.countByEventTypeAndStatus(
                OutboxEvent.EVENT_WHATSAPP_NOTIFY, OutboxEvent.STATUS_SENT);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /** A mutable sales bucket used while tiling the window. */
    private static final class Bucket {
        private final LocalDate start;
        private final LocalDate end;
        private final String label;
        private BigDecimal sales = BigDecimal.ZERO;

        private Bucket(LocalDate start, LocalDate end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }
}
