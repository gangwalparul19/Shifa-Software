package com.shifa.oms.dashboard;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.ChannelSplit;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.ChannelTotals;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.Kpis;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.PaymentMix;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.Payments;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.Performance;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.PortalQueues;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.Queues;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.RankRow;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.ShopifyQueues;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.StageCount;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.TrendPoint;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the channel-aware admin dashboard: Portal vs Shopify vs combined, over a
 * period, in a single pass over the order book.
 *
 * <p>Rules (kept identical to {@code ChannelSummaryService} and the reports):
 * <ul>
 *   <li><b>Portal</b> = every non-Shopify order; <b>Shopify</b> = {@code source = SHOPIFY}.</li>
 *   <li><b>Revenue</b> excludes REJECTED, PAYMENT_REJECTED and CANCELLED orders;
 *       <b>order counts</b> include every order placed in the window.</li>
 *   <li>Money received on a Portal order is <b>collected by our team</b>; money
 *       received on a Shopify order was <b>paid on Shopify</b> — kept separate.</li>
 *   <li>Work queues are a live snapshot (not limited to the period).</li>
 * </ul>
 */
@Service
public class ChannelDashboardService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int TOP_N = 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** Display labels for the lifecycle stages (match the Orders page tabs). */
    private static final Map<OrderStatusGroup, String> STAGE_LABELS = new EnumMap<>(OrderStatusGroup.class);

    static {
        STAGE_LABELS.put(OrderStatusGroup.PENDING_APPROVAL, "Pending Approval");
        STAGE_LABELS.put(OrderStatusGroup.PROCESSING, "Processing");
        STAGE_LABELS.put(OrderStatusGroup.SHIPPED, "Shipped");
        STAGE_LABELS.put(OrderStatusGroup.DELIVERED, "Delivered");
        STAGE_LABELS.put(OrderStatusGroup.FAILED_RETURNED, "Returned / Failed");
        STAGE_LABELS.put(OrderStatusGroup.CANCELLED, "Cancelled");
        STAGE_LABELS.put(OrderStatusGroup.REJECTED, "Rejected");
    }

    private static final Map<OrderStatus, OrderStatusGroup> STATUS_TO_GROUP = new EnumMap<>(OrderStatus.class);

    static {
        for (OrderStatusGroup group : OrderStatusGroup.values()) {
            for (OrderStatus status : group.statuses()) {
                STATUS_TO_GROUP.put(status, group);
            }
        }
    }

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final UserRepository userRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final Clock clock;

    @Autowired
    public ChannelDashboardService(OrderRepository orderRepository,
                                   ReceivableRepository receivableRepository,
                                   UserRepository userRepository,
                                   OrderShipmentRepository shipmentRepository) {
        this(orderRepository, receivableRepository, userRepository, shipmentRepository,
                Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock. */
    ChannelDashboardService(OrderRepository orderRepository,
                            ReceivableRepository receivableRepository,
                            UserRepository userRepository,
                            OrderShipmentRepository shipmentRepository,
                            Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.userRepository = userRepository;
        this.shipmentRepository = shipmentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChannelDashboardResponse dashboard(DashboardChannel channel, MetricsPeriod period,
                                              SalesBucket bucket, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        DateRange window = period.resolve(today, from, to);
        DateRange previous = window.previousPeriod();
        SalesBucket effectiveBucket = bucket != null ? bucket : period.defaultBucket();

        List<OrderEntity> all = orderRepository.findAll();
        List<OrderEntity> current = new ArrayList<>();
        List<OrderEntity> prior = new ArrayList<>();
        for (OrderEntity o : all) {
            LocalDate d = dateOf(o);
            if (window.contains(d)) {
                current.add(o);
            } else if (previous != null && previous.contains(d)) {
                prior.add(o);
            }
        }
        List<OrderEntity> scoped = filter(current, channel);
        List<OrderEntity> scopedPrior = filter(prior, channel);

        return new ChannelDashboardResponse(
                channel.name(), period.name(), effectiveBucket.name(), window.from(), window.to(),
                split(current, prior),
                kpis(scoped, scopedPrior),
                trend(current, prior, channel, window, previous, effectiveBucket),
                pipeline(scoped),
                queues(all, channel),
                payments(scoped, all, channel, today),
                performance(scoped, channel));
    }

    // --- Channel split ------------------------------------------------------

    private ChannelSplit split(List<OrderEntity> current, List<OrderEntity> prior) {
        BigDecimal allRevenue = revenue(current);
        return new ChannelSplit(
                totals(current, prior, allRevenue),
                totals(filter(current, DashboardChannel.PORTAL), filter(prior, DashboardChannel.PORTAL), allRevenue),
                totals(filter(current, DashboardChannel.SHOPIFY), filter(prior, DashboardChannel.SHOPIFY), allRevenue),
                totals(filter(current, DashboardChannel.STORE), filter(prior, DashboardChannel.STORE), allRevenue));
    }

    private ChannelTotals totals(List<OrderEntity> cur, List<OrderEntity> prev, BigDecimal allRevenue) {
        BigDecimal revenue = revenue(cur);
        BigDecimal prevRevenue = revenue(prev);
        return new ChannelTotals(
                cur.size(), revenue, avg(revenue, revenueCount(cur)), pct(revenue, allRevenue),
                prev.size(), prevRevenue, change(revenue, prevRevenue),
                change(BigDecimal.valueOf(cur.size()), BigDecimal.valueOf(prev.size())));
    }

    // --- KPIs ---------------------------------------------------------------

    private Kpis kpis(List<OrderEntity> cur, List<OrderEntity> prev) {
        long delivered = 0;
        long failed = 0;
        long cancelledRejected = 0;
        long inProgress = 0;
        BigDecimal cod = BigDecimal.ZERO;
        for (OrderEntity o : cur) {
            OrderStatusGroup g = STATUS_TO_GROUP.get(o.getOrderStatus());
            if (g == OrderStatusGroup.DELIVERED) {
                delivered++;
            } else if (g == OrderStatusGroup.FAILED_RETURNED) {
                failed++;
            } else if (g == OrderStatusGroup.CANCELLED || g == OrderStatusGroup.REJECTED) {
                cancelledRejected++;
            } else if (g != null) {
                inProgress++;
            }
            if (isRevenue(o)) {
                cod = cod.add(nz(o.getCustomerOutstanding()));
            }
        }
        BigDecimal revenue = revenue(cur);
        BigDecimal prevRevenue = revenue(prev);
        BigDecimal success = delivered + failed == 0 ? null
                : pct(BigDecimal.valueOf(delivered), BigDecimal.valueOf(delivered + failed));
        return new Kpis(
                cur.size(), revenue, avg(revenue, revenueCount(cur)),
                delivered, inProgress, failed, cancelledRejected, success, scale(cod),
                prev.size(), prevRevenue, change(revenue, prevRevenue),
                change(BigDecimal.valueOf(cur.size()), BigDecimal.valueOf(prev.size())));
    }

    // --- Trend --------------------------------------------------------------

    private List<TrendPoint> trend(List<OrderEntity> current, List<OrderEntity> prior, DashboardChannel channel,
                                   DateRange window, DateRange previous, SalesBucket bucket) {
        List<LocalDate[]> cur = buckets(window, bucket);
        List<LocalDate[]> prev = previous == null ? List.of() : buckets(previous, bucket);
        List<String> labels = labels(window, bucket);
        List<TrendPoint> points = new ArrayList<>(cur.size());
        for (int i = 0; i < cur.size(); i++) {
            LocalDate[] b = cur.get(i);
            // The stacked trend keeps two series (portal + shopify) for the chart.
            // Store revenue is folded into the "portal" (our-own-orders) series when
            // it is in scope, so the stacked TOTAL always reconciles with the selected
            // channel's revenue without reshaping the chart. For a single STORE-channel
            // view this means the store revenue appears as the first series.
            BigDecimal portal = BigDecimal.ZERO;
            if (channel.includesPortal() || channel == DashboardChannel.ALL) {
                portal = portal.add(revenueIn(current, DashboardChannel.PORTAL, b));
            }
            if (channel == DashboardChannel.STORE || channel == DashboardChannel.ALL) {
                portal = portal.add(revenueIn(current, DashboardChannel.STORE, b));
            }
            BigDecimal shopify = channel.includesShopify()
                    ? revenueIn(current, DashboardChannel.SHOPIFY, b) : BigDecimal.ZERO;
            BigDecimal prevTotal = i < prev.size() ? revenueIn(prior, channel, prev.get(i)) : BigDecimal.ZERO;
            points.add(new TrendPoint(labels.get(i), scale(portal), scale(shopify),
                    scale(portal.add(shopify)), scale(prevTotal)));
        }
        return points;
    }

    private BigDecimal revenueIn(List<OrderEntity> orders, DashboardChannel channel, LocalDate[] bucket) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            LocalDate d = dateOf(o);
            if (d != null && !d.isBefore(bucket[0]) && !d.isAfter(bucket[1])
                    && channel.matches(o.getSource()) && isRevenue(o)) {
                sum = sum.add(nz(o.getTotalAmount()));
            }
        }
        return sum;
    }

    /** Buckets tiling a bounded window: [start, end] pairs. */
    private static List<LocalDate[]> buckets(DateRange window, SalesBucket bucket) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate from = window.from();
        LocalDate to = window.to();
        if (from == null || to == null || from.isAfter(to)) {
            return out;
        }
        switch (bucket) {
            case DAY -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    out.add(new LocalDate[] {d, d});
                }
            }
            case WEEK -> {
                for (LocalDate s = MetricsPeriod.startOfWeek(from); !s.isAfter(to); s = s.plusWeeks(1)) {
                    out.add(new LocalDate[] {s, s.plusDays(6)});
                }
            }
            case MONTH -> {
                for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
                    out.add(new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()});
                }
            }
        }
        return out;
    }

    /** Labels in the same format as the existing sales graph (ISO day, "Wk yyyy-MM-dd", "yyyy-MM"). */
    private static List<String> labels(DateRange window, SalesBucket bucket) {
        List<String> out = new ArrayList<>();
        for (LocalDate[] b : buckets(window, bucket)) {
            out.add(switch (bucket) {
                case DAY -> b[0].toString();
                case WEEK -> "Wk " + b[0];
                case MONTH -> YearMonth.from(b[0]).toString();
            });
        }
        return out;
    }

    // --- Pipeline -----------------------------------------------------------

    private List<StageCount> pipeline(List<OrderEntity> orders) {
        Map<OrderStatusGroup, Long> counts = new EnumMap<>(OrderStatusGroup.class);
        for (OrderEntity o : orders) {
            OrderStatusGroup g = STATUS_TO_GROUP.get(o.getOrderStatus());
            if (g != null) {
                counts.merge(g, 1L, Long::sum);
            }
        }
        List<StageCount> out = new ArrayList<>();
        for (OrderStatusGroup g : OrderStatusGroup.values()) {
            out.add(new StageCount(g.name(), STAGE_LABELS.getOrDefault(g, g.name()), counts.getOrDefault(g, 0L)));
        }
        return out;
    }

    // --- Queues (live snapshot) ---------------------------------------------

    private Queues queues(List<OrderEntity> all, DashboardChannel channel) {
        PortalQueues portal = null;
        if (channel.includesPortal()) {
            long pending = 0;
            long toPack = 0;
            long handover = 0;
            long dispatch = 0;
            for (OrderEntity o : all) {
                if (o.getSource() == OrderSource.SHOPIFY || o.getOrderStatus() == null) {
                    continue;
                }
                switch (o.getOrderStatus()) {
                    case PENDING_ADMIN_APPROVAL -> pending++;
                    case APPROVED, LABEL_GENERATED -> toPack++;
                    case PACKED -> handover++;
                    case HANDED_TO_DELIVERY -> dispatch++;
                    default -> { }
                }
            }
            portal = new PortalQueues(pending, toPack, handover, dispatch);
        }
        ShopifyQueues shopify = null;
        if (channel.includesShopify()) {
            // Candidates for the "No tracking ID" tile: Shopify orders still before
            // the courier (pending approval, or label-generated non-in-house). A
            // QuikShip order deliberately STAYS at Label Generated after its AWB is
            // allotted, so a status-only count wrongly flags every allotted order as
            // "stuck". Join the shipment and count ONLY those with no AWB yet — i.e.
            // genuinely waiting for a tracking id (matches the Shopify Sync page).
            List<Long> candidateIds = new ArrayList<>();
            List<Long> assigned = new ArrayList<>();
            for (OrderEntity o : all) {
                if (o.getSource() != OrderSource.SHOPIFY) {
                    continue;
                }
                OrderStatus s = o.getOrderStatus();
                if (s == OrderStatus.PENDING_ADMIN_APPROVAL
                        || (s == OrderStatus.LABEL_GENERATED && !o.isInHouseDelivery())) {
                    candidateIds.add(o.getId());
                } else if (s == OrderStatus.COURIER_ASSIGNED) {
                    assigned.add(o.getId());
                }
            }
            // Which candidate orders already have an allotted AWB (not stuck).
            Set<Long> withAwb = new java.util.HashSet<>();
            if (!candidateIds.isEmpty()) {
                for (OrderShipment sh : shipmentRepository.findByOrderIdIn(candidateIds)) {
                    if (sh.getAwb() != null && !sh.getAwb().isBlank()) {
                        withAwb.add(sh.getOrderId());
                    }
                }
            }
            long stuck = candidateIds.stream().filter(id -> !withAwb.contains(id)).count();
            long printed = 0;
            if (!assigned.isEmpty()) {
                for (OrderShipment sh : shipmentRepository.findByOrderIdIn(assigned)) {
                    if (sh.isLabelPrinted()) {
                        printed++;
                    }
                }
            }
            shopify = new ShopifyQueues(stuck, assigned.size() - printed, printed);
        }
        return new Queues(portal, shopify);
    }

    // --- Payments & cash ----------------------------------------------------

    private Payments payments(List<OrderEntity> scoped, List<OrderEntity> all, DashboardChannel channel,
                              LocalDate today) {
        Map<PaymentStatus, long[]> counts = new EnumMap<>(PaymentStatus.class);
        Map<PaymentStatus, BigDecimal> amounts = new EnumMap<>(PaymentStatus.class);
        BigDecimal team = BigDecimal.ZERO;
        BigDecimal shopify = BigDecimal.ZERO;
        BigDecimal cod = BigDecimal.ZERO;
        for (OrderEntity o : scoped) {
            if (!isRevenue(o)) {
                continue;
            }
            PaymentStatus ps = o.getPaymentStatus() == null ? PaymentStatus.COD : o.getPaymentStatus();
            counts.computeIfAbsent(ps, k -> new long[1])[0]++;
            amounts.merge(ps, nz(o.getTotalAmount()), BigDecimal::add);
            if (o.getSource() == OrderSource.SHOPIFY) {
                shopify = shopify.add(nz(o.getAmountReceived()));
            } else {
                team = team.add(nz(o.getAmountReceived()));
            }
            cod = cod.add(nz(o.getCustomerOutstanding()));
        }
        List<PaymentMix> mix = new ArrayList<>();
        mix.add(mixRow(PaymentStatus.FULLY_PAID, "Prepaid", counts, amounts));
        mix.add(mixRow(PaymentStatus.PARTIALLY_PAID, "Part paid", counts, amounts));
        mix.add(mixRow(PaymentStatus.COD, "Pay on delivery", counts, amounts));

        // Courier-side money: unsettled receivables, attributed to the order's channel.
        Map<Long, OrderSource> sourceById = new HashMap<>();
        for (OrderEntity o : all) {
            sourceById.put(o.getId(), o.getSource());
        }
        BigDecimal codCourier = unsettled(ReceivableType.COD_RECEIVABLE, channel, sourceById);
        BigDecimal claims = unsettled(ReceivableType.CLAIM_RECEIVABLE, channel, sourceById);

        long todayOrders = 0;
        BigDecimal todayTeam = BigDecimal.ZERO;
        BigDecimal todayShopify = BigDecimal.ZERO;
        for (OrderEntity o : all) {
            if (!today.equals(dateOf(o)) || !channel.matches(o.getSource())) {
                continue;
            }
            todayOrders++;
            if (!isRevenue(o)) {
                continue;
            }
            if (o.getSource() == OrderSource.SHOPIFY) {
                todayShopify = todayShopify.add(nz(o.getAmountReceived()));
            } else {
                todayTeam = todayTeam.add(nz(o.getAmountReceived()));
            }
        }
        return new Payments(mix, scale(team), scale(shopify), scale(cod), codCourier, claims,
                todayOrders, scale(todayTeam), scale(todayShopify));
    }

    private static PaymentMix mixRow(PaymentStatus status, String label,
                                     Map<PaymentStatus, long[]> counts, Map<PaymentStatus, BigDecimal> amounts) {
        long[] c = counts.get(status);
        return new PaymentMix(status.name(), label, c == null ? 0 : c[0], scale(amounts.get(status)));
    }

    private BigDecimal unsettled(ReceivableType type, DashboardChannel channel, Map<Long, OrderSource> sourceById) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceivableEntity e : receivableRepository.findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(type)) {
            OrderSource source = sourceById.get(e.getOrderId());
            if (channel == DashboardChannel.ALL || (source != null && channel.matches(source))) {
                total = total.add(nz(e.getAmount()));
            }
        }
        return scale(total);
    }

    // --- Performance --------------------------------------------------------

    private Performance performance(List<OrderEntity> scoped, DashboardChannel channel) {
        Map<Long, Agg> bySalesperson = new HashMap<>();
        Map<String, Agg> byProduct = new HashMap<>();
        Map<String, Agg> byState = new HashMap<>();
        for (OrderEntity o : scoped) {
            if (!isRevenue(o)) {
                continue;
            }
            BigDecimal amount = nz(o.getTotalAmount());
            if (channel.includesPortal() && o.getSource() != OrderSource.SHOPIFY && o.getCreatedBy() != null) {
                bySalesperson.computeIfAbsent(o.getCreatedBy(), k -> new Agg()).add(1, amount);
            }
            String state = o.getState() == null || o.getState().isBlank() ? "Unknown" : o.getState().trim();
            byState.computeIfAbsent(state, k -> new Agg()).add(1, amount);
            for (OrderLineItem line : o.getLineItems()) {
                String name = line.getProductName() == null || line.getProductName().isBlank()
                        ? "Unnamed item" : line.getProductName().trim();
                byProduct.computeIfAbsent(name, k -> new Agg()).add(line.getQuantity(), nz(line.getLineTotal()));
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!bySalesperson.isEmpty()) {
            for (User u : userRepository.findAllById(bySalesperson.keySet())) {
                names.put(u.getId(), u.getFullName());
            }
        }
        Map<String, Agg> salespeople = new LinkedHashMap<>();
        bySalesperson.forEach((id, agg) -> salespeople.put(names.getOrDefault(id, "User #" + id), agg));
        return new Performance(top(salespeople), top(byProduct), top(byState));
    }

    private static List<RankRow> top(Map<String, Agg> aggs) {
        return aggs.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Agg> e) -> e.getValue().revenue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .map(e -> new RankRow(e.getKey(), e.getValue().count, scale(e.getValue().revenue)))
                .toList();
    }

    private static final class Agg {
        private long count;
        private BigDecimal revenue = BigDecimal.ZERO;

        void add(long c, BigDecimal r) {
            count += c;
            revenue = revenue.add(r);
        }
    }

    // --- Helpers ------------------------------------------------------------

    private static List<OrderEntity> filter(List<OrderEntity> orders, DashboardChannel channel) {
        if (channel == DashboardChannel.ALL) {
            return orders;
        }
        List<OrderEntity> out = new ArrayList<>();
        for (OrderEntity o : orders) {
            if (channel.matches(o.getSource())) {
                out.add(o);
            }
        }
        return out;
    }

    private static LocalDate dateOf(OrderEntity o) {
        return o.getCreatedAt() == null ? null : o.getCreatedAt().toLocalDate();
    }

    private static boolean isRevenue(OrderEntity o) {
        return !NON_REVENUE.contains(o.getOrderStatus());
    }

    private static BigDecimal revenue(List<OrderEntity> orders) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            if (isRevenue(o)) {
                sum = sum.add(nz(o.getTotalAmount()));
            }
        }
        return scale(sum);
    }

    private static long revenueCount(List<OrderEntity> orders) {
        long n = 0;
        for (OrderEntity o : orders) {
            if (isRevenue(o)) {
                n++;
            }
        }
        return n;
    }

    private static BigDecimal avg(BigDecimal total, long count) {
        return count == 0 ? scale(BigDecimal.ZERO) : total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** part / whole as a 0–100 percentage (1 dp); 0 when whole is zero. */
    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** % change from previous to current (1 dp), or null when there is nothing to compare against. */
    static BigDecimal change(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
