package com.shifa.oms.performance;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.performance.dto.SalespersonDailyPoint;
import com.shifa.oms.performance.dto.SalespersonLeadMetrics;
import com.shifa.oms.performance.dto.SalespersonOrderRow;
import com.shifa.oms.performance.dto.SalespersonPerformanceDetail;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Salesperson 360" — admin-facing performance analytics per salesperson
 * (FEATURE request), the sales-team analog of Customer 360. Powers a leaderboard
 * to compare the team and a per-salesperson detail (recent daily trend, latest
 * orders, and lead/CRM conversion) so an admin can track performance daily and
 * act on weak performers.
 *
 * <p>Read-only aggregation over the existing {@code orders}, {@code leads} and
 * {@code users} tables — no new tables. Uses the injectable {@link Clock} pattern
 * (see {@code OrderProductSalesLookup}) for "this month" / "today" / last-N-days
 * windows so it is deterministically testable.
 */
@Service
public class SalespersonPerformanceService {

    /** Orders that never shipped are excluded from revenue (matches P&L). */
    private static final Set<OrderStatus> NON_REVENUE =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED);

    /** Concluded successful deliveries. */
    private static final Set<OrderStatus> DELIVERED =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    /** Concluded failed deliveries. */
    private static final Set<OrderStatus> FAILED =
            EnumSet.of(OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
                    OrderStatus.RTO, OrderStatus.COURIER_LOST);

    private static final int DEFAULT_TREND_DAYS = 14;
    private static final int MAX_TREND_DAYS = 60;
    private static final int RECENT_ORDERS_LIMIT = 10;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final Clock clock;

    @Autowired
    public SalespersonPerformanceService(OrderRepository orderRepository,
                                         UserRepository userRepository,
                                         LeadRepository leadRepository) {
        this(orderRepository, userRepository, leadRepository, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    SalespersonPerformanceService(OrderRepository orderRepository,
                                  UserRepository userRepository,
                                  LeadRepository leadRepository,
                                  Clock clock) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.leadRepository = leadRepository;
        this.clock = clock;
    }

    // --- Leaderboard --------------------------------------------------------

    /** Headline metrics for every salesperson, best (this month's revenue) first. */
    @Transactional(readOnly = true)
    public List<SalespersonPerformanceSummary> leaderboard() {
        return leaderboardFor(null);
    }

    /**
     * Headline metrics for a specific set of salespeople (a team lead's team),
     * best (this month's revenue) first. A {@code null} {@code memberIds} means
     * "all salespeople" (the admin-wide leaderboard); an empty set yields an
     * empty list (a team lead with no assigned salespeople).
     */
    @Transactional(readOnly = true)
    public List<SalespersonPerformanceSummary> leaderboardFor(java.util.Collection<Long> memberIds) {
        if (memberIds != null && memberIds.isEmpty()) {
            return List.of();
        }
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime dayStart = today.atStartOfDay();

        Map<Long, OrderRepository.SalespersonOrderAggregate> byId = new HashMap<>();
        for (OrderRepository.SalespersonOrderAggregate agg
                : orderRepository.salespersonOrderStats(monthStart, dayStart)) {
            if (agg.getSalespersonId() != null) {
                byId.put(agg.getSalespersonId(), agg);
            }
        }

        Set<Long> filter = memberIds == null ? null : new java.util.HashSet<>(memberIds);
        List<SalespersonPerformanceSummary> rows = new ArrayList<>();
        for (User u : userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)) {
            if (filter != null && !filter.contains(u.getId())) {
                continue;
            }
            rows.add(summaryOf(u, byId.get(u.getId())));
        }
        rows.sort(Comparator
                .comparing(SalespersonPerformanceSummary::revenueThisMonth,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingLong(SalespersonPerformanceSummary::ordersThisMonth).reversed()));
        return rows;
    }

    private static SalespersonPerformanceSummary summaryOf(
            User u, OrderRepository.SalespersonOrderAggregate agg) {
        long delivered = agg == null ? 0 : agg.getDeliveredCount();
        long failed = agg == null ? 0 : agg.getFailedCount();
        return new SalespersonPerformanceSummary(
                u.getId(),
                u.getUsername(),
                u.getFullName(),
                u.isActive(),
                u.getVerificationStatus() == null ? null : u.getVerificationStatus().name(),
                agg == null ? 0 : agg.getOrdersTotal(),
                agg == null ? 0 : agg.getOrdersThisMonth(),
                agg == null ? 0 : agg.getOrdersToday(),
                nz(agg == null ? null : agg.getRevenueTotal()),
                nz(agg == null ? null : agg.getRevenueThisMonth()),
                delivered,
                failed,
                successRate(delivered, failed),
                nz(agg == null ? null : agg.getCodOutstanding()));
    }

    // --- Detail (one salesperson) ------------------------------------------

    /**
     * The full 360 for a salesperson: headline summary + a {@code days}-day daily
     * trend + their latest orders + lead/CRM metrics.
     *
     * @param id   the salesperson user id (must be a SALESPERSON)
     * @param days trend window in days (clamped to 1..60, default 14)
     */
    @Transactional(readOnly = true)
    public SalespersonPerformanceDetail detail(Long id, Integer days) {
        User u = requireSalesperson(id);
        int trendDays = clampDays(days);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime dayStart = today.atStartOfDay();

        List<OrderEntity> orders = orderRepository.findAllScoped(id); // newest first

        long ordersTotal = orders.size();
        long ordersThisMonth = 0;
        long ordersToday = 0;
        long delivered = 0;
        long failed = 0;
        BigDecimal revenueTotal = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;
        BigDecimal codOutstanding = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            OrderStatus status = o.getOrderStatus();
            boolean revenue = !NON_REVENUE.contains(status);
            BigDecimal total = o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount();
            LocalDateTime createdAt = o.getCreatedAt();
            if (revenue) {
                revenueTotal = revenueTotal.add(total);
            }
            if (createdAt != null && !createdAt.isBefore(monthStart)) {
                ordersThisMonth++;
                if (revenue) {
                    revenueThisMonth = revenueThisMonth.add(total);
                }
            }
            if (createdAt != null && !createdAt.isBefore(dayStart)) {
                ordersToday++;
            }
            if (DELIVERED.contains(status)) {
                delivered++;
            } else if (FAILED.contains(status)) {
                failed++;
            }
            if (o.getCustomerOutstanding() != null) {
                codOutstanding = codOutstanding.add(o.getCustomerOutstanding());
            }
        }

        SalespersonPerformanceSummary summary = new SalespersonPerformanceSummary(
                u.getId(), u.getUsername(), u.getFullName(), u.isActive(),
                u.getVerificationStatus() == null ? null : u.getVerificationStatus().name(),
                ordersTotal, ordersThisMonth, ordersToday,
                revenueTotal, revenueThisMonth, delivered, failed,
                successRate(delivered, failed), codOutstanding);

        List<SalespersonDailyPoint> trend = trend(orders, today, trendDays);
        List<SalespersonOrderRow> recent = orders.stream()
                .limit(RECENT_ORDERS_LIMIT)
                .map(SalespersonOrderRow::from)
                .toList();
        SalespersonLeadMetrics leads = leadMetrics(id, today);

        return new SalespersonPerformanceDetail(summary, trend, recent, leads);
    }

    private List<SalespersonDailyPoint> trend(List<OrderEntity> orders, LocalDate today, int days) {
        LocalDate start = today.minusDays(days - 1L);
        // Seed every day in the window (oldest→newest) so gaps render as zero.
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> revenue = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            counts.put(d, new long[1]);
            revenue.put(d, BigDecimal.ZERO);
        }
        for (OrderEntity o : orders) {
            if (o.getCreatedAt() == null) {
                continue;
            }
            LocalDate d = o.getCreatedAt().toLocalDate();
            if (counts.containsKey(d)) {
                counts.get(d)[0]++;
                if (!NON_REVENUE.contains(o.getOrderStatus()) && o.getTotalAmount() != null) {
                    revenue.put(d, revenue.get(d).add(o.getTotalAmount()));
                }
            }
        }
        List<SalespersonDailyPoint> points = new ArrayList<>(days);
        for (LocalDate d : counts.keySet()) {
            points.add(new SalespersonDailyPoint(d, counts.get(d)[0], revenue.get(d)));
        }
        return points;
    }

    private SalespersonLeadMetrics leadMetrics(Long ownerId, LocalDate today) {
        List<LeadEntity> leads = leadRepository.findAllScoped(ownerId);
        long total = leads.size();
        long won = leads.stream().filter(l -> l.getStatus() == LeadStatus.WON).count();
        long lost = leads.stream().filter(l -> l.getStatus() == LeadStatus.LOST).count();
        long active = total - won - lost;
        double conversionRate = total == 0 ? 0.0
                : BigDecimal.valueOf(won * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue();
        long dueFollowUps = leadRepository.findDueFollowUps(ownerId, today).size();
        return new SalespersonLeadMetrics(total, won, lost, active, conversionRate, dueFollowUps);
    }

    // --- Helpers ------------------------------------------------------------

    private User requireSalesperson(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist."));
        if (user.getRole() != Role.SALESPERSON) {
            throw new ValidationException("This account is not a salesperson.");
        }
        return user;
    }

    private static int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_TREND_DAYS;
        }
        return Math.max(1, Math.min(MAX_TREND_DAYS, days));
    }

    private static double successRate(long delivered, long failed) {
        long concluded = delivered + failed;
        if (concluded == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(delivered * 100.0 / concluded)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
