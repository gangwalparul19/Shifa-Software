package com.shifa.oms.salesperson;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.performance.PerformancePeriod;
import com.shifa.oms.performance.SalesTarget;
import com.shifa.oms.performance.SalesTargetRepository;
import com.shifa.oms.salesperson.dto.LeaderboardResponse;
import com.shifa.oms.salesperson.dto.MyDayResponse;
import com.shifa.oms.salesperson.dto.ReorderDueCustomer;
import com.shifa.oms.salesperson.dto.WinBackCustomer;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.PageRequest;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
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
 * whole business). The figures come from database aggregates in
 * {@link MyDayQueryRepository}, so the cost of a dashboard open no longer grows
 * with the total number of orders ever punched; only the monthly-target lookup
 * and the small final lists are handled here.
 */
@Service
public class MyDayService {

    /** Business timezone for "today"/"this month" (India). */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Orders excluded from revenue/targets (matches the P&L / performance rules). */
    private static final Set<OrderStatus> EXCLUDED =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** Default lapsed-window (days) for the win-back list. */
    private static final int DEFAULT_WINBACK_DAYS = 60;

    /** Cap on the win-back list size (highest-value first). */
    private static final int WINBACK_LIMIT = 100;

    /** How many rows the leaderboard returns. */
    private static final int LEADERBOARD_SIZE = 10;

    /** Bounds standing in for "no limit" when the leaderboard period is Overall. */
    private static final LocalDateTime OPEN_START = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime OPEN_END = LocalDateTime.of(2100, 1, 1, 0, 0);

    /** Placeholder creator id passed to the queries when the scope is "everyone". */
    private static final Long NO_CREATOR = -1L;

    private final MyDayQueryRepository queries;
    private final SalespersonScopeResolver scopeResolver;
    private final SalesTargetRepository targetRepository;
    private final UserRepository userRepository;
    private final Clock clock = Clock.system(BUSINESS_ZONE);

    public MyDayService(MyDayQueryRepository queries,
                        SalespersonScopeResolver scopeResolver,
                        SalesTargetRepository targetRepository,
                        UserRepository userRepository) {
        this.queries = queries;
        this.scopeResolver = scopeResolver;
        this.targetRepository = targetRepository;
        this.userRepository = userRepository;
    }

    /** Today + month-to-date figures and payments-to-chase for the caller. */
    @Transactional(readOnly = true)
    public MyDayResponse forCaller(AuthPrincipal principal) {
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        boolean anyCreator = createdBy == null;
        Long creator = anyCreator ? NO_CREATOR : createdBy;

        LocalDate today = LocalDate.now(clock);
        LocalDate firstOfMonth = YearMonth.from(today).atDay(1);

        MyDayQueryRepository.TotalsRow todayRow = queries.totalsBetween(
                EXCLUDED, anyCreator, creator, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        // Month-to-date has no upper bound, as before.
        MyDayQueryRepository.TotalsRow monthRow = queries.totalsSince(
                EXCLUDED, anyCreator, creator, firstOfMonth.atStartOfDay());
        // Payments to chase: an active order still carrying a balance.
        MyDayQueryRepository.TotalsRow pendingRow = queries.pendingPayments(EXCLUDED, anyCreator, creator);

        long ordersToday = count(todayRow.getOrderCount());
        long monthOrders = count(monthRow.getOrderCount());
        long pendingCount = count(pendingRow.getOrderCount());
        BigDecimal revenueToday = nz(todayRow.getAmount());
        BigDecimal monthRevenue = nz(monthRow.getAmount());
        BigDecimal pendingAmount = nz(pendingRow.getAmount());

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
        boolean anyCreator = createdBy == null;
        Long creator = anyCreator ? NO_CREATOR : createdBy;
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(window);

        // A customer is lapsed when the date of their last order is on or before the
        // cutoff, i.e. their last order time is before the start of the day after it.
        // Rejected/cancelled orders and blank mobiles are left out in the query; the
        // database ranks by lifetime value and applies the list cap.
        List<MyDayQueryRepository.WinBackRow> rows = queries.winBackCandidates(
                EXCLUDED, anyCreator, creator, cutoff.plusDays(1).atStartOfDay(),
                PageRequest.of(0, WINBACK_LIMIT));

        List<String> mobiles = new ArrayList<>(rows.size());
        for (MyDayQueryRepository.WinBackRow r : rows) {
            mobiles.add(r.getMobile());
        }
        Map<String, String> names = latestNames(mobiles, anyCreator, creator);

        List<WinBackCustomer> result = new ArrayList<>(rows.size());
        for (MyDayQueryRepository.WinBackRow r : rows) {
            if (r.getLastOrderAt() == null) {
                continue;
            }
            LocalDate last = r.getLastOrderAt().toLocalDate();
            long daysSince = ChronoUnit.DAYS.between(last, today);
            result.add(new WinBackCustomer(
                    r.getMobile().trim(), names.get(r.getMobile()), last, daysSince,
                    count(r.getOrderCount()), scale(nz(r.getTotalValue()))));
        }
        return result;
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
        boolean anyCreator = createdBy == null;
        Long creator = anyCreator ? NO_CREATOR : createdBy;
        LocalDate today = LocalDate.now(clock);

        // One row per repeat customer (>= 2 dated orders) — grows with customers, not orders.
        record Candidate(String rawMobile, LocalDate last, LocalDate predicted, long avgInterval,
                         long overdue, long orderCount, BigDecimal totalValue) {}
        List<Candidate> due = new ArrayList<>();
        for (MyDayQueryRepository.CadenceRow r : queries.repeatCustomers(EXCLUDED, anyCreator, creator)) {
            long orderCount = count(r.getOrderCount());
            if (orderCount < 2 || r.getFirstOrderAt() == null || r.getLastOrderAt() == null) {
                continue; // need at least two orders to infer a cadence
            }
            LocalDate first = r.getFirstOrderAt().toLocalDate();
            LocalDate last = r.getLastOrderAt().toLocalDate();
            long span = ChronoUnit.DAYS.between(first, last);
            long avgInterval = Math.round((double) span / (orderCount - 1));
            if (avgInterval <= 0) {
                continue; // multiple orders same day — no meaningful cadence
            }
            LocalDate predicted = last.plusDays(avgInterval);
            long overdue = ChronoUnit.DAYS.between(predicted, today); // + = overdue, - = future
            if (overdue < -REORDER_GRACE_DAYS) {
                continue; // not due yet
            }
            due.add(new Candidate(r.getMobile(), last, predicted, avgInterval, overdue,
                    orderCount, scale(nz(r.getTotalValue()))));
        }
        // Most overdue first (largest overdueDays), then highest value.
        due.sort(Comparator.comparingLong(Candidate::overdue).reversed()
                .thenComparing(Comparator.comparing(Candidate::totalValue).reversed()));
        List<Candidate> top = due.size() > WINBACK_LIMIT ? due.subList(0, WINBACK_LIMIT) : due;

        // Names only for the customers actually returned.
        List<String> mobiles = new ArrayList<>(top.size());
        for (Candidate c : top) {
            mobiles.add(c.rawMobile());
        }
        Map<String, String> names = latestNames(mobiles, anyCreator, creator);

        List<ReorderDueCustomer> result = new ArrayList<>(top.size());
        for (Candidate c : top) {
            result.add(new ReorderDueCustomer(
                    c.rawMobile().trim(), names.get(c.rawMobile()), c.last(), c.predicted(),
                    c.avgInterval(), c.overdue(), c.orderCount(), c.totalValue()));
        }
        return result;
    }

    /**
     * Sales leaderboard for a period (all salespeople, by revenue) plus the caller's own rank and
     * consecutive-day order streak — light gamification.
     *
     * @param period   the period to rank over (the controller defaults it to this month)
     * @param customFrom first day of a {@link PerformancePeriod#CUSTOM} range (inclusive)
     * @param customTo   last day of a {@link PerformancePeriod#CUSTOM} range (inclusive)
     */
    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard(AuthPrincipal principal, PerformancePeriod period,
                                           LocalDate customFrom, LocalDate customTo) {
        Long me = principal.userId();
        LocalDate today = LocalDate.now(clock);
        PerformancePeriod.Window window = period.resolve(today, customFrom, customTo);

        // Whole business, in the period, attributed non-excluded orders, grouped by creator. An
        // "Overall" window has no bounds, so it is queried with a very early start and late end.
        LocalDateTime windowFrom = window.from() != null ? window.from() : OPEN_START;
        LocalDateTime windowTo = window.to() != null ? window.to() : OPEN_END;
        Map<Long, MyDayQueryRepository.CreatorRow> byCreator = new LinkedHashMap<>();
        for (MyDayQueryRepository.CreatorRow row : queries.revenueByCreatorBetween(
                windowFrom, windowTo, EXCLUDED)) {
            byCreator.putIfAbsent(row.getCreatorId(), row);
        }

        // Streak counts any order the caller punched (regardless of status).
        Set<LocalDate> myOrderDates = new HashSet<>();
        if (me != null) {
            for (Object value : queries.orderDates(me)) {
                LocalDate date = toLocalDate(value);
                if (date != null) {
                    myOrderDates.add(date);
                }
            }
        }

        // Resolve creators to users and keep only salespeople (the leaderboard is
        // for the sales team; admin-punched orders are excluded).
        Map<Long, User> users = userRepository.findAllById(byCreator.keySet()).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        record Scored(long id, String name, BigDecimal revenue, long orders) {}
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<Long, MyDayQueryRepository.CreatorRow> e : byCreator.entrySet()) {
            User u = users.get(e.getKey());
            if (u == null || u.getRole() != Role.SALESPERSON) {
                continue;
            }
            String name = (u.getFullName() == null || u.getFullName().isBlank())
                    ? u.getUsername() : u.getFullName();
            scored.add(new Scored(e.getKey(), name, nz(e.getValue().getRevenue()),
                    count(e.getValue().getOrderCount())));
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
        String periodLabel = period == PerformancePeriod.CUSTOM ? window.label() : period.label();
        return new LeaderboardResponse(rows, myRank, scale(myRevenue), streakDays(myOrderDates, today),
                period.name(), periodLabel, window.firstDay(), window.lastDay());
    }

    /**
     * Name on each mobile's most recent dated, non-excluded order within the caller's
     * scope, keyed by the mobile exactly as the aggregate query returned it.
     */
    private Map<String, String> latestNames(Collection<String> mobiles, boolean anyCreator, Long creator) {
        Map<String, String> names = new HashMap<>();
        if (mobiles.isEmpty()) {
            return names;
        }
        // Newest first, so the first name seen for a mobile is its latest order's.
        for (MyDayQueryRepository.NameRow r : queries.latestOrderNames(mobiles, EXCLUDED, anyCreator, creator)) {
            names.putIfAbsent(r.getMobile(), r.getCustomerName());
        }
        return names;
    }

    /** Converts whatever date type the database cast returns into a {@link LocalDate}. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date utilDate) {
            return new java.sql.Date(utilDate.getTime()).toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return null;
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

    private static long count(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
