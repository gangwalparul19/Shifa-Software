package com.shifa.oms.salesperson;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.performance.SalesTarget;
import com.shifa.oms.performance.SalesTargetRepository;
import com.shifa.oms.salesperson.dto.LeaderboardResponse;
import com.shifa.oms.salesperson.dto.MyDayResponse;
import com.shifa.oms.salesperson.dto.ReorderDueCustomer;
import com.shifa.oms.salesperson.dto.WinBackCustomer;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "My Day" + "Win-back" application service for a salesperson (self-service).
 *
 * <p>Both views are scoped to the caller's own orders via
 * {@link SalespersonScopeResolver} (an ADMIN caller is unscoped and sees the
 * whole business). Everything is computed in-memory from the caller's order list
 * — a salesperson's volume is modest — so there are no new queries beyond the
 * existing scoped fetch and the monthly-target lookup.
 */
@Service
public class MyDayService {

    /** Business timezone for "today"/"this month" (India). */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Orders excluded from revenue/targets (matches the P&L / performance rules). */
    private static final Set<OrderStatus> EXCLUDED =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED);

    /** Default lapsed-window (days) for the win-back list. */
    private static final int DEFAULT_WINBACK_DAYS = 60;

    /** Cap on the win-back list size (highest-value first). */
    private static final int WINBACK_LIMIT = 100;

    /** How many rows the leaderboard returns. */
    private static final int LEADERBOARD_SIZE = 10;

    private final OrderRepository orderRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final SalesTargetRepository targetRepository;
    private final UserRepository userRepository;
    private final Clock clock = Clock.system(BUSINESS_ZONE);

    public MyDayService(OrderRepository orderRepository,
                        SalespersonScopeResolver scopeResolver,
                        SalesTargetRepository targetRepository,
                        UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.scopeResolver = scopeResolver;
        this.targetRepository = targetRepository;
        this.userRepository = userRepository;
    }

    /** Today + month-to-date figures and payments-to-chase for the caller. */
    @Transactional(readOnly = true)
    public MyDayResponse forCaller(AuthPrincipal principal) {
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        List<OrderEntity> orders = orderRepository.findAllScoped(createdBy);

        LocalDate today = LocalDate.now(clock);
        LocalDate firstOfMonth = YearMonth.from(today).atDay(1);

        long ordersToday = 0;
        long monthOrders = 0;
        long pendingCount = 0;
        BigDecimal revenueToday = BigDecimal.ZERO;
        BigDecimal monthRevenue = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;

        for (OrderEntity o : orders) {
            boolean counts = !EXCLUDED.contains(o.getOrderStatus());
            LocalDate placed = o.getCreatedAt() == null ? null : o.getCreatedAt().toLocalDate();
            if (counts && placed != null) {
                if (placed.equals(today)) {
                    ordersToday++;
                    revenueToday = revenueToday.add(nz(o.getTotalAmount()));
                }
                if (!placed.isBefore(firstOfMonth)) {
                    monthOrders++;
                    monthRevenue = monthRevenue.add(nz(o.getTotalAmount()));
                }
            }
            // Payments to chase: an active order still carrying a balance.
            if (counts) {
                BigDecimal remaining = nz(o.getRemainingAmount());
                if (remaining.signum() > 0) {
                    pendingCount++;
                    pendingAmount = pendingAmount.add(remaining);
                }
            }
        }

        BigDecimal target = createdBy == null ? null
                : targetRepository.findBySalespersonIdAndPeriodMonth(createdBy, firstOfMonth)
                        .map(SalesTarget::getTargetAmount).orElse(null);
        int progressPct = 0;
        if (target != null && target.signum() > 0) {
            progressPct = monthRevenue.multiply(BigDecimal.valueOf(100))
                    .divide(target, 0, RoundingMode.FLOOR).intValue();
        }

        return new MyDayResponse(
                ordersToday, scale(revenueToday),
                monthOrders, scale(monthRevenue),
                target == null ? null : scale(target), progressPct,
                pendingCount, scale(pendingAmount));
    }

    /**
     * Lapsed customers (no order in the last {@code days} days), ranked by
     * lifetime value — the salesperson's daily "who to call" list.
     */
    @Transactional(readOnly = true)
    public List<WinBackCustomer> winBack(AuthPrincipal principal, Integer days) {
        int window = (days == null || days <= 0) ? DEFAULT_WINBACK_DAYS : days;
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        List<OrderEntity> orders = orderRepository.findAllScoped(createdBy);
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(window);

        // Aggregate by mobile (skip rejected/cancelled and blank mobiles).
        Map<String, Agg> byMobile = new LinkedHashMap<>();
        for (OrderEntity o : orders) {
            if (EXCLUDED.contains(o.getOrderStatus())) {
                continue;
            }
            String mobile = o.getCustomerMobile();
            if (mobile == null || mobile.isBlank()) {
                continue;
            }
            LocalDateTime created = o.getCreatedAt();
            Agg agg = byMobile.computeIfAbsent(mobile.trim(), m -> new Agg());
            agg.orderCount++;
            agg.totalValue = agg.totalValue.add(nz(o.getTotalAmount()));
            if (created != null && (agg.lastOrder == null || created.isAfter(agg.lastOrder))) {
                agg.lastOrder = created;
                agg.name = o.getCustomerName();
            }
        }

        List<WinBackCustomer> result = new ArrayList<>();
        for (Map.Entry<String, Agg> e : byMobile.entrySet()) {
            Agg a = e.getValue();
            if (a.lastOrder == null) {
                continue;
            }
            LocalDate last = a.lastOrder.toLocalDate();
            if (last.isAfter(cutoff)) {
                continue; // ordered recently — not lapsed
            }
            long daysSince = ChronoUnit.DAYS.between(last, today);
            result.add(new WinBackCustomer(
                    e.getKey(), a.name, last, daysSince, a.orderCount, scale(a.totalValue)));
        }
        result.sort(Comparator.comparing(WinBackCustomer::totalValue).reversed());
        return result.size() > WINBACK_LIMIT ? result.subList(0, WINBACK_LIMIT) : result;
    }

    /** Grace window (days): a customer within this many days of their predicted date is "due". */
    private static final int REORDER_GRACE_DAYS = 5;

    /**
     * Customers predicted to be due for a repeat order, from their purchase
     * cadence (avg interval between past orders projected from the last order).
     * Only customers with ≥2 orders whose predicted date is within {@code GRACE}
     * days (or past) are returned, most-overdue first.
     */
    @Transactional(readOnly = true)
    public List<ReorderDueCustomer> reorderDue(AuthPrincipal principal) {
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        List<OrderEntity> orders = orderRepository.findAllScoped(createdBy);
        LocalDate today = LocalDate.now(clock);

        Map<String, Cadence> byMobile = new LinkedHashMap<>();
        for (OrderEntity o : orders) {
            if (EXCLUDED.contains(o.getOrderStatus()) || o.getCreatedAt() == null) {
                continue;
            }
            String mobile = o.getCustomerMobile();
            if (mobile == null || mobile.isBlank()) {
                continue;
            }
            Cadence c = byMobile.computeIfAbsent(mobile.trim(), m -> new Cadence());
            LocalDate placed = o.getCreatedAt().toLocalDate();
            c.dates.add(placed);
            c.totalValue = c.totalValue.add(nz(o.getTotalAmount()));
            if (c.last == null || o.getCreatedAt().isAfter(c.lastAt)) {
                c.lastAt = o.getCreatedAt();
                c.last = placed;
                c.name = o.getCustomerName();
            }
        }

        List<ReorderDueCustomer> due = new ArrayList<>();
        for (Map.Entry<String, Cadence> e : byMobile.entrySet()) {
            Cadence c = e.getValue();
            if (c.dates.size() < 2 || c.last == null) {
                continue; // need at least two orders to infer a cadence
            }
            LocalDate first = c.dates.stream().min(LocalDate::compareTo).orElse(c.last);
            long span = ChronoUnit.DAYS.between(first, c.last);
            long avgInterval = Math.round((double) span / (c.dates.size() - 1));
            if (avgInterval <= 0) {
                continue; // multiple orders same day — no meaningful cadence
            }
            LocalDate predicted = c.last.plusDays(avgInterval);
            long overdue = ChronoUnit.DAYS.between(predicted, today); // + = overdue, - = future
            if (overdue < -REORDER_GRACE_DAYS) {
                continue; // not due yet
            }
            due.add(new ReorderDueCustomer(
                    e.getKey(), c.name, c.last, predicted, avgInterval, overdue,
                    c.dates.size(), scale(c.totalValue)));
        }
        // Most overdue first (largest overdueDays), then highest value.
        due.sort(Comparator.comparingLong(ReorderDueCustomer::overdueDays).reversed()
                .thenComparing(Comparator.comparing(ReorderDueCustomer::totalValue).reversed()));
        return due.size() > WINBACK_LIMIT ? due.subList(0, WINBACK_LIMIT) : due;
    }

    /**
     * This-month sales leaderboard (all salespeople, by revenue) plus the
     * caller's own rank and consecutive-day order streak — light gamification.
     */
    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard(AuthPrincipal principal) {
        Long me = principal.userId();
        List<OrderEntity> all = orderRepository.findAllScoped(null); // whole business
        LocalDate today = LocalDate.now(clock);
        LocalDate firstOfMonth = YearMonth.from(today).atDay(1);

        Map<Long, MonthAgg> byCreator = new LinkedHashMap<>();
        Set<LocalDate> myOrderDates = new HashSet<>();
        for (OrderEntity o : all) {
            Long creator = o.getCreatedBy();
            LocalDate placed = o.getCreatedAt() == null ? null : o.getCreatedAt().toLocalDate();
            // Streak counts any order the caller punched (regardless of status).
            if (creator != null && creator.equals(me) && placed != null) {
                myOrderDates.add(placed);
            }
            if (creator == null || placed == null || EXCLUDED.contains(o.getOrderStatus())) {
                continue;
            }
            if (placed.isBefore(firstOfMonth)) {
                continue;
            }
            MonthAgg a = byCreator.computeIfAbsent(creator, k -> new MonthAgg());
            a.revenue = a.revenue.add(nz(o.getTotalAmount()));
            a.orders++;
        }

        // Resolve creators to users and keep only salespeople (the leaderboard is
        // for the sales team; admin-punched orders are excluded).
        Map<Long, User> users = userRepository.findAllById(byCreator.keySet()).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        record Scored(long id, String name, BigDecimal revenue, long orders) {}
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<Long, MonthAgg> e : byCreator.entrySet()) {
            User u = users.get(e.getKey());
            if (u == null || u.getRole() != Role.SALESPERSON) {
                continue;
            }
            String name = (u.getFullName() == null || u.getFullName().isBlank())
                    ? u.getUsername() : u.getFullName();
            scored.add(new Scored(e.getKey(), name, e.getValue().revenue, e.getValue().orders));
        }
        scored.sort(Comparator.comparing(Scored::revenue).reversed()
                .thenComparing(Comparator.comparingLong(Scored::orders).reversed()));

        List<LeaderboardResponse.LeaderboardRow> rows = new ArrayList<>();
        Integer myRank = null;
        BigDecimal myRevenue = BigDecimal.ZERO;
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            boolean isMe = me != null && s.id() == me;
            if (isMe) {
                myRank = i + 1;
                myRevenue = s.revenue();
            }
            if (i < LEADERBOARD_SIZE) {
                rows.add(new LeaderboardResponse.LeaderboardRow(
                        i + 1, s.id(), s.name(), scale(s.revenue()), s.orders(), isMe));
            }
        }
        return new LeaderboardResponse(rows, myRank, scale(myRevenue), streakDays(myOrderDates, today));
    }

    /** Consecutive days (ending today or yesterday) on which the caller punched an order. */
    private static int streakDays(Set<LocalDate> dates, LocalDate today) {
        if (dates.isEmpty()) {
            return 0;
        }
        LocalDate anchor;
        if (dates.contains(today)) {
            anchor = today;
        } else if (dates.contains(today.minusDays(1))) {
            anchor = today.minusDays(1);
        } else {
            return 0; // streak broken (no order today or yesterday)
        }
        int streak = 0;
        LocalDate d = anchor;
        while (dates.contains(d)) {
            streak++;
            d = d.minusDays(1);
        }
        return streak;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** Mutable per-mobile accumulator (win-back). */
    private static final class Agg {
        private String name;
        private LocalDateTime lastOrder;
        private long orderCount;
        private BigDecimal totalValue = BigDecimal.ZERO;
    }

    /** Mutable per-mobile accumulator with order dates (reorder-cadence). */
    private static final class Cadence {
        private final List<LocalDate> dates = new ArrayList<>();
        private String name;
        private LocalDate last;
        private LocalDateTime lastAt;
        private BigDecimal totalValue = BigDecimal.ZERO;
    }

    /** Mutable per-salesperson month accumulator (leaderboard). */
    private static final class MonthAgg {
        private BigDecimal revenue = BigDecimal.ZERO;
        private long orders;
    }
}
