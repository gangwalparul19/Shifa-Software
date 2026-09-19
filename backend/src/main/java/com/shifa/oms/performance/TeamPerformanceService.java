package com.shifa.oms.performance;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadReportRecord;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.performance.dto.DirectReportPerformanceResponse;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.performance.dto.TeamCoachingFlag;
import com.shifa.oms.performance.dto.TeamOrderRow;
import com.shifa.oms.performance.dto.TeamPeriodSummary;
import com.shifa.oms.performance.dto.TeamPerformanceResponse;
import com.shifa.oms.performance.dto.TeamSourceConversion;
import com.shifa.oms.performance.dto.TeamWorkSummary;
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

/** Server-scoped Team Lead / ADMIN performance rollup. */
@Service
public class TeamPerformanceService {

    private static final Set<OrderStatus> NON_REVENUE =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED);
    private static final Set<OrderStatus> DELIVERED = EnumSet.of(
            OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);
    private static final Set<OrderStatus> FAILED = EnumSet.of(
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO, OrderStatus.REDISPATCH);
    private static final Set<OrderStatus> RTO = EnumSet.of(OrderStatus.RTO, OrderStatus.REDISPATCH);
    private static final Set<OrderStatus> OPEN = EnumSet.complementOf(EnumSet.of(
            OrderStatus.CLOSED, OrderStatus.COD_COLLECTED, OrderStatus.REJECTED,
            OrderStatus.CANCELLED, OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO, OrderStatus.REDISPATCH));

    private final SalespersonPerformanceService performanceService;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final OrderRepository orderRepository;
    private final SalesTargetRepository salesTargetRepository;
    private final Clock clock;

    @Autowired
    public TeamPerformanceService(SalespersonPerformanceService performanceService,
                                  LeadRepository leadRepository,
                                  UserRepository userRepository,
                                  SalespersonScopeResolver scopeResolver,
                                  OrderRepository orderRepository,
                                  SalesTargetRepository salesTargetRepository) {
        this(performanceService, leadRepository, userRepository, scopeResolver,
                orderRepository, salesTargetRepository, Clock.systemDefaultZone());
    }

    /** Backward-compatible constructor used by existing focused tests. */
    public TeamPerformanceService(SalespersonPerformanceService performanceService,
                                  LeadRepository leadRepository,
                                  UserRepository userRepository,
                                  SalespersonScopeResolver scopeResolver) {
        this(performanceService, leadRepository, userRepository, scopeResolver, null, null,
                Clock.systemDefaultZone());
    }

    TeamPerformanceService(SalespersonPerformanceService performanceService,
                           LeadRepository leadRepository,
                           UserRepository userRepository,
                           SalespersonScopeResolver scopeResolver,
                           OrderRepository orderRepository,
                           SalesTargetRepository salesTargetRepository,
                           Clock clock) {
        this.performanceService = performanceService;
        this.leadRepository = leadRepository;
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
        this.orderRepository = orderRepository;
        this.salesTargetRepository = salesTargetRepository;
        this.clock = clock;
    }

    /** Current-month-compatible default used by existing callers. */
    @Transactional(readOnly = true)
    public TeamPerformanceResponse forCaller(AuthPrincipal actor) {
        LocalDate today = LocalDate.now(clock);
        return forCaller(actor, today.withDayOfMonth(1), today);
    }

    /** Selected reporting window; scope is always resolved from the authenticated caller. */
    @Transactional(readOnly = true)
    public TeamPerformanceResponse forCaller(AuthPrincipal actor, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            LocalDate today = LocalDate.now(clock);
            from = today.withDayOfMonth(1);
            to = today;
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Team performance end date cannot be before start date.");
        }

        List<Long> memberIds = resolveMemberIds(actor);
        List<SalespersonPerformanceSummary> baseRows = performanceService.leaderboardFor(memberIds);
        List<OrderEntity> teamOrders = memberIds.isEmpty() || orderRepository == null
                ? List.of()
                : safe(orderRepository.findAllScopedIn(memberIds));
        List<LeadEntity> teamLeads = memberIds.isEmpty()
                ? List.of()
                : safe(leadRepository.findAllScopedIn(memberIds));
        List<OrderEntity> ownOrders = actor.role() == Role.TEAM_LEAD && orderRepository != null
                ? safe(orderRepository.findAllScoped(actor.userId()))
                : List.of();
        List<LeadEntity> ownLeads = actor.role() == Role.TEAM_LEAD
                ? safe(leadRepository.findAllScoped(actor.userId()))
                : List.of();

        TeamPeriodSummary period = periodSummary(teamOrders, teamLeads, memberIds, from, to);
        TeamPeriodSummary ownPeriod = actor.role() == Role.TEAM_LEAD
                ? periodSummary(ownOrders, ownLeads, List.of(actor.userId()), from, to)
                : null;
        TeamPeriodSummary combinedPeriod = actor.role() == Role.TEAM_LEAD
                ? combinePeriods(period, ownPeriod)
                : period;
        Map<Long, MemberWindow> memberWindows = memberWindows(teamOrders, teamLeads, memberIds, from, to);
        List<SalespersonPerformanceSummary> leaderboard = baseRows.stream()
                .map(row -> enrich(row, memberWindows.get(row.id())))
                .sorted(Comparator.comparing(SalespersonPerformanceSummary::revenueInPeriod).reversed()
                        .thenComparing(Comparator.comparingLong(SalespersonPerformanceSummary::ordersInPeriod).reversed())
                        .thenComparing(SalespersonPerformanceSummary::fullName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        SalespersonPerformanceSummary ownPerformance = actor.role() == Role.TEAM_LEAD
                ? ownSummary(actor, ownOrders, ownPeriod)
                : null;
        Map<Long, String> salespersonNames = namesFor(memberIds, actor);
        List<TeamOrderRow> ownOrderRows = toOrderRows(ownOrders, salespersonNames);
        List<TeamOrderRow> teamOrderRows = toOrderRows(teamOrders, salespersonNames);

        long ordersTotal = 0;
        long ordersThisMonth = 0;
        long delivered = 0;
        long failed = 0;
        BigDecimal revenueTotal = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;
        BigDecimal codOutstanding = BigDecimal.ZERO;
        for (SalespersonPerformanceSummary row : leaderboard) {
            ordersTotal += row.ordersTotal();
            ordersThisMonth += row.ordersThisMonth();
            delivered += row.deliveredCount();
            failed += row.failedCount();
            revenueTotal = revenueTotal.add(nz(row.revenueTotal()));
            revenueThisMonth = revenueThisMonth.add(nz(row.revenueThisMonth()));
            codOutstanding = codOutstanding.add(nz(row.codOutstanding()));
        }

        Map<String, long[]> bySource = new LinkedHashMap<>();
        long leadsTotal = 0;
        long leadsWon = 0;
        for (LeadEntity lead : teamLeads) {
            LeadReportRecord record = LeadReportRecord.from(lead);
            String source = record.source() == null ? "OTHER" : record.source().name();
            long[] cell = bySource.computeIfAbsent(source, ignored -> new long[2]);
            cell[0]++;
            leadsTotal++;
            if (record.status() == LeadStatus.WON) {
                cell[1]++;
                leadsWon++;
            }
        }
        List<TeamSourceConversion> leadSources = new ArrayList<>();
        bySource.forEach((source, counts) -> leadSources.add(
                new TeamSourceConversion(source, counts[0], counts[1], pct(counts[1], counts[0]))));
        leadSources.sort(Comparator.comparingDouble(TeamSourceConversion::conversionRate).reversed()
                .thenComparing(Comparator.comparingLong(TeamSourceConversion::leads).reversed()));

        TeamWorkSummary work = workSummary(teamOrders, teamLeads, leaderboard);
        List<TeamCoachingFlag> coaching = coachingFlags(leaderboard, work);
        String topPerformer = leaderboard.isEmpty() ? null : leaderboard.get(0).fullName();
        String topSource = leadSources.stream().findFirst().map(TeamSourceConversion::source).orElse(null);

        return new TeamPerformanceResponse(
                memberIds.size(), ordersTotal, ordersThisMonth, revenueTotal, revenueThisMonth,
                delivered, failed, pct(delivered, delivered + failed), codOutstanding,
                leadsTotal, leadsWon, pct(leadsWon, leadsTotal), topPerformer, topSource,
                leaderboard, leadSources, period, work, coaching,
                ownPerformance, ownPeriod, combinedPeriod, ownOrderRows, teamOrderRows);
    }

    /**
     * Detail access remains unchanged: TEAM_LEAD may only request an assigned
     * salesperson; ADMIN remains global. Unknown/out-of-scope ids share a 404.
     */
    @Transactional(readOnly = true)
    public DirectReportPerformanceResponse detailForCaller(AuthPrincipal actor,
                                                            Long salespersonId,
                                                            Integer days) {
        if (actor.role() == Role.TEAM_LEAD
                && !scopeResolver.teamMemberScope(actor).orElse(List.of()).contains(salespersonId)) {
            throw new ResourceNotFoundException("Salesperson " + salespersonId + " does not exist.");
        }
        User salesperson = userRepository.findById(salespersonId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Salesperson " + salespersonId + " does not exist."));
        return DirectReportPerformanceResponse.of(salesperson,
                performanceService.detail(salespersonId, days));
    }

    private TeamPeriodSummary periodSummary(List<OrderEntity> orders, List<LeadEntity> leads,
                                            List<Long> memberIds, LocalDate from, LocalDate to) {
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(java.time.temporal.ChronoUnit.DAYS.between(from, to));
        WindowTotals current = totals(orders, from, to.plusDays(1));
        WindowTotals previous = totals(orders, previousFrom, from);
        long followUps = leads.stream().filter(lead -> lead.getFollowUpDate() != null
                && !lead.getFollowUpDate().isAfter(LocalDate.now(clock))
                && lead.getStatus() != LeadStatus.WON && lead.getStatus() != LeadStatus.LOST).count();
        BigDecimal target = targetFor(memberIds, from);
        Double progress = target.signum() == 0 ? null : pctDecimal(current.revenue, target);
        return new TeamPeriodSummary(from, to, previousFrom, previousTo,
                current.orders, current.revenue, aov(current.revenue, current.revenueOrders),
                previous.orders, previous.revenue, current.todayOrders, current.todayRevenue,
                current.failed, current.rto, current.customerOutstanding,
                current.pendingPaymentCount, current.pendingPaymentAmount, followUps,
                target, current.revenue, progress);
    }

    private Map<Long, MemberWindow> memberWindows(List<OrderEntity> orders, List<LeadEntity> leads,
                                                  List<Long> memberIds, LocalDate from, LocalDate to) {
        Map<Long, MemberWindow> result = new HashMap<>();
        for (Long id : memberIds) {
            result.put(id, new MemberWindow());
        }
        LocalDate today = LocalDate.now(clock);
        for (OrderEntity order : orders) {
            MemberWindow window = result.get(order.getCreatedBy());
            if (window == null || order.getCreatedAt() == null) {
                continue;
            }
            LocalDate day = order.getCreatedAt().toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            window.orders++;
            boolean revenue = !NON_REVENUE.contains(order.getOrderStatus());
            if (revenue) {
                window.revenue = window.revenue.add(nz(order.getTotalAmount()));
                window.revenueOrders++;
            }
            if (FAILED.contains(order.getOrderStatus())) window.failed++;
            if (RTO.contains(order.getOrderStatus())) window.rto++;
            if (day.equals(today)) window.ordersToday++;
        }
        for (LeadEntity lead : leads) {
            MemberWindow window = result.get(lead.getOwnerUserId());
            if (window != null && lead.getFollowUpDate() != null
                    && !lead.getFollowUpDate().isAfter(today)
                    && lead.getStatus() != LeadStatus.WON && lead.getStatus() != LeadStatus.LOST) {
                window.dueFollowUps++;
            }
        }
        return result;
    }

    private SalespersonPerformanceSummary enrich(SalespersonPerformanceSummary row, MemberWindow window) {
        if (window == null) return row;
        return new SalespersonPerformanceSummary(
                row.id(), row.username(), row.fullName(), row.active(), row.verificationStatus(),
                row.ordersTotal(), row.ordersThisMonth(), row.ordersToday(), row.revenueTotal(), row.revenueThisMonth(),
                row.deliveredCount(), row.failedCount(), row.successRate(), row.codOutstanding(),
                window.orders, window.revenue, aov(window.revenue, window.revenueOrders),
                window.rto, window.dueFollowUps);
    }

    private SalespersonPerformanceSummary ownSummary(AuthPrincipal actor, List<OrderEntity> orders,
                                                     TeamPeriodSummary period) {
        User user = userRepository.findById(actor.userId()).orElse(null);
        String name = user == null ? actor.username() : user.getFullName();
        String username = user == null ? actor.username() : user.getUsername();
        boolean active = user == null || user.isActive();
        String verification = user == null || user.getVerificationStatus() == null
                ? null : user.getVerificationStatus().name();
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.withDayOfMonth(1);
        long total = 0, month = 0, todayOrders = 0, delivered = 0, failed = 0, rto = 0;
        BigDecimal lifetimeRevenue = BigDecimal.ZERO, monthRevenue = BigDecimal.ZERO, outstanding = BigDecimal.ZERO;
        for (OrderEntity order : orders) {
            total++;
            boolean revenue = !NON_REVENUE.contains(order.getOrderStatus());
            BigDecimal amount = nz(order.getTotalAmount());
            if (revenue) lifetimeRevenue = lifetimeRevenue.add(amount);
            if (order.getCreatedAt() != null) {
                LocalDate day = order.getCreatedAt().toLocalDate();
                if (!day.isBefore(monthStart)) { month++; if (revenue) monthRevenue = monthRevenue.add(amount); }
                if (day.equals(today)) todayOrders++;
            }
            if (DELIVERED.contains(order.getOrderStatus())) delivered++;
            if (FAILED.contains(order.getOrderStatus())) failed++;
            if (RTO.contains(order.getOrderStatus())) rto++;
            outstanding = outstanding.add(nz(order.getCustomerOutstanding()));
        }
        return new SalespersonPerformanceSummary(
                actor.userId(), username, name, active, verification, total, month, todayOrders,
                lifetimeRevenue, monthRevenue, delivered, failed, pct(delivered, delivered + failed), outstanding,
                period.orders(), period.revenue(), period.averageOrderValue(), rto, period.followUpsDue());
    }

    private Map<Long, String> namesFor(List<Long> memberIds, AuthPrincipal actor) {
        List<Long> ids = new ArrayList<>(memberIds);
        if (actor.role() == Role.TEAM_LEAD && !ids.contains(actor.userId())) ids.add(actor.userId());
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) names.put(user.getId(), user.getFullName());
        if (actor.role() == Role.TEAM_LEAD) names.putIfAbsent(actor.userId(), actor.username());
        return names;
    }

    private List<TeamOrderRow> toOrderRows(List<OrderEntity> orders, Map<Long, String> names) {
        return orders.stream().filter(order -> order.getId() != null).limit(100)
                .map(order -> new TeamOrderRow(order.getId(), order.getOrderCode(), order.getCustomerName(),
                        names.getOrDefault(order.getCreatedBy(), "Unknown"), order.getTotalAmount(),
                        order.getOrderStatus(), order.getPaymentStatus(), order.getCreatedAt()))
                .toList();
    }

    private TeamPeriodSummary combinePeriods(TeamPeriodSummary team, TeamPeriodSummary own) {
        if (own == null) return team;
        BigDecimal revenue = nz(team.revenue()).add(nz(own.revenue()));
        long orders = team.orders() + own.orders();
        BigDecimal previous = nz(team.previousRevenue()).add(nz(own.previousRevenue()));
        return new TeamPeriodSummary(team.from(), team.to(), team.previousFrom(), team.previousTo(),
                orders, revenue, aov(revenue, revenueOrders(team, own)),
                team.previousOrders() + own.previousOrders(), previous,
                team.ordersToday() + own.ordersToday(), nz(team.revenueToday()).add(nz(own.revenueToday())),
                team.failed() + own.failed(), team.rto() + own.rto(),
                nz(team.customerOutstanding()).add(nz(own.customerOutstanding())),
                team.pendingPaymentCount() + own.pendingPaymentCount(),
                nz(team.pendingPaymentAmount()).add(nz(own.pendingPaymentAmount())),
                team.followUpsDue() + own.followUpsDue(),
                nz(team.target()).add(nz(own.target())), revenue,
                nz(team.target()).add(nz(own.target())).signum() == 0 ? null
                        : pctDecimal(revenue, nz(team.target()).add(nz(own.target()))));
    }

    private long revenueOrders(TeamPeriodSummary first, TeamPeriodSummary second) {
        BigDecimal revenue = nz(first.revenue()).add(nz(second.revenue()));
        BigDecimal aov = nz(first.averageOrderValue()).add(nz(second.averageOrderValue()));
        return aov.signum() == 0 ? 0 : revenue.divide(aov, 0, RoundingMode.HALF_UP).longValue();
    }
    private TeamWorkSummary workSummary(List<OrderEntity> orders, List<LeadEntity> leads,
                                         List<SalespersonPerformanceSummary> rows) {
        long approval = orders.stream().filter(o -> o.getOrderStatus() == OrderStatus.PENDING_ADMIN_APPROVAL).count();
        long payment = orders.stream().filter(o -> o.getPaymentVerificationStatus() == PaymentVerificationStatus.PENDING).count();
        long failed = orders.stream().filter(o -> FAILED.contains(o.getOrderStatus())).count();
        long rto = orders.stream().filter(o -> RTO.contains(o.getOrderStatus())).count();
        long due = leads.stream().filter(l -> l.getFollowUpDate() != null
                && !l.getFollowUpDate().isAfter(LocalDate.now(clock))
                && l.getStatus() != LeadStatus.WON && l.getStatus() != LeadStatus.LOST).count();
        long inactive = rows.stream().filter(r -> !r.active() || r.ordersInPeriod() == 0).count();
        return new TeamWorkSummary(approval, payment, failed, rto, due, inactive, inactive);
    }

    private List<TeamCoachingFlag> coachingFlags(List<SalespersonPerformanceSummary> rows,
                                                  TeamWorkSummary work) {
        List<TeamCoachingFlag> flags = new ArrayList<>();
        for (SalespersonPerformanceSummary row : rows) {
            if (!row.active()) {
                flags.add(new TeamCoachingFlag(row.id(), row.fullName(), "INACTIVE", "HIGH",
                        "Account inactive", "Confirm whether this salesperson should receive team work."));
            } else if (row.ordersInPeriod() == 0) {
                flags.add(new TeamCoachingFlag(row.id(), row.fullName(), "NO_ACTIVITY", "WARNING",
                        "No order activity in selected period", "Review pipeline, follow-ups, and availability."));
            } else if (row.dueFollowUps() > 3) {
                flags.add(new TeamCoachingFlag(row.id(), row.fullName(), "FOLLOW_UPS", "WARNING",
                        "Follow-ups need attention", row.dueFollowUps() + " due or overdue follow-ups."));
            } else if (row.rtoCount() > 0) {
                flags.add(new TeamCoachingFlag(row.id(), row.fullName(), "DELIVERY", "INFO",
                        "Delivery outcomes need review", row.rtoCount() + " RTO/redispatch outcome(s) in selected period."));
            }
        }
        return flags;
    }

    private BigDecimal targetFor(List<Long> memberIds, LocalDate from) {
        if (salesTargetRepository == null) return BigDecimal.ZERO;
        LocalDate month = from.withDayOfMonth(1);
        List<SalesTarget> targets = salesTargetRepository.findByPeriodMonth(month);
        return targets.stream()
                .filter(target -> memberIds.contains(target.getSalespersonId()))
                .map(SalesTarget::getTargetAmount).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private WindowTotals totals(List<OrderEntity> orders, LocalDate from, LocalDate exclusiveTo) {
        WindowTotals total = new WindowTotals();
        LocalDate today = LocalDate.now(clock);
        for (OrderEntity order : orders) {
            if (order.getCreatedAt() == null) continue;
            LocalDate day = order.getCreatedAt().toLocalDate();
            if (day.isBefore(from) || !day.isBefore(exclusiveTo)) continue;
            total.orders++;
            boolean revenue = !NON_REVENUE.contains(order.getOrderStatus());
            if (revenue) {
                total.revenue = total.revenue.add(nz(order.getTotalAmount()));
                total.revenueOrders++;
            }
            if (FAILED.contains(order.getOrderStatus())) total.failed++;
            if (RTO.contains(order.getOrderStatus())) total.rto++;
            if (day.equals(today)) {
                total.todayOrders++;
                if (revenue) total.todayRevenue = total.todayRevenue.add(nz(order.getTotalAmount()));
            }
            if (OPEN.contains(order.getOrderStatus()) && nz(order.getRemainingAmount()).signum() > 0) {
                total.pendingPaymentCount++;
                total.pendingPaymentAmount = total.pendingPaymentAmount.add(nz(order.getRemainingAmount()));
            }
            total.customerOutstanding = total.customerOutstanding.add(nz(order.getCustomerOutstanding()));
        }
        return total;
    }

    private List<Long> resolveMemberIds(AuthPrincipal actor) {
        return scopeResolver.teamMemberScope(actor).orElseGet(() ->
                userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)
                        .stream().map(User::getId).toList());
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static BigDecimal aov(BigDecimal revenue, long orders) {
        return orders == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
    }

    private static double pct(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double pctDecimal(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? 0.0 : numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 1, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class MemberWindow {
        long orders;
        long revenueOrders;
        long ordersToday;
        long failed;
        long rto;
        long dueFollowUps;
        BigDecimal revenue = BigDecimal.ZERO;
    }

    private static final class WindowTotals {
        long orders;
        long revenueOrders;
        long todayOrders;
        long failed;
        long rto;
        long pendingPaymentCount;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal todayRevenue = BigDecimal.ZERO;
        BigDecimal customerOutstanding = BigDecimal.ZERO;
        BigDecimal pendingPaymentAmount = BigDecimal.ZERO;
    }
}
