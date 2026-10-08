package com.shifa.oms.dashboard;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamCallOut;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamOverviewRow;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.performance.SalespersonPerformanceService;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * Builds the "Team-wise sales with status" admin dashboard view: for every
 * {@code TEAM_LEAD} (e.g. "Team Sameer", "Team Zeeshan"), the salespeople
 * assigned to them, their combined orders/revenue/delivery KPIs, current lead
 * pipeline, and a ranked call-out list of leads due or overdue for a follow-up
 * — so an admin can see where each team is heading and jump straight to the
 * leads that need a call, without opening each team lead's own dashboard.
 *
 * <p>A "team" here is the salespeople with {@code users.team_lead_id = <lead>}
 * PLUS the team lead themselves: the lead now punches orders too, so the team's
 * order/revenue/delivery totals INCLUDE the lead's own orders and the lead
 * appears as a member row (marked "(Team Lead)"). The LEAD PIPELINE stays scoped
 * to the assigned salespeople only, since leads can only be owned by a
 * SALESPERSON or ADMIN (a team lead cannot capture leads), and {@code memberCount}
 * reflects the assigned-salespeople team size. A salesperson with no team lead
 * assigned is rolled into {@link TeamsOverviewResponse#unassigned()} rather than
 * silently dropped.
 */
@Service
public class TeamsOverviewService {

    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);
    private static final Set<OrderStatus> DELIVERED = EnumSet.of(
            OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);
    private static final Set<OrderStatus> FAILED = EnumSet.of(
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO, OrderStatus.REDISPATCH);

    /** Cap on how many call-outs are returned per team (most-overdue first). */
    private static final int MAX_CALL_OUTS = 25;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final LeadRepository leadRepository;
    private final SalespersonPerformanceService performanceService;
    private final Clock clock;

    @Autowired
    public TeamsOverviewService(UserRepository userRepository,
                                OrderRepository orderRepository,
                                LeadRepository leadRepository,
                                SalespersonPerformanceService performanceService) {
        this(userRepository, orderRepository, leadRepository, performanceService, Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock. */
    TeamsOverviewService(UserRepository userRepository,
                         OrderRepository orderRepository,
                         LeadRepository leadRepository,
                         SalespersonPerformanceService performanceService,
                         Clock clock) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.leadRepository = leadRepository;
        this.performanceService = performanceService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TeamsOverviewResponse overview() {
        LocalDate today = LocalDate.now(clock);
        List<User> leads = userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD);
        List<User> allSalespeople = userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON);
        Map<Long, String> namesById = new HashMap<>();
        for (User u : allSalespeople) {
            namesById.put(u.getId(), u.getFullName());
        }

        // Compute the per-salesperson leaderboard ONCE (it aggregates the whole
        // orders table + lists all salespeople) and index it by id, instead of
        // re-running that full-table aggregate for every team in rowFor().
        Map<Long, SalespersonPerformanceSummary> leaderboardById = new HashMap<>();
        for (SalespersonPerformanceSummary s : performanceService.leaderboard()) {
            leaderboardById.put(s.id(), s);
        }

        // The team lead's own name, for the synthetic "self" member row + totals.
        for (User lead : leads) {
            namesById.putIfAbsent(lead.getId(), lead.getFullName());
        }

        List<TeamOverviewRow> rows = new ArrayList<>();
        java.util.Set<Long> assigned = new java.util.HashSet<>();
        for (User lead : leads) {
            List<Long> memberIds = userRepository.findIdsByTeamLeadId(lead.getId());
            assigned.addAll(memberIds);
            // The team lead now also punches orders (team-lead order entry), so a
            // team's sales must INCLUDE the lead's own orders. Pass the lead id so
            // rowFor folds the lead's own orders into every order-derived total and
            // shows the lead as a member row — while the assigned-salespeople set
            // still drives the team size and the lead pipeline (a lead owns no leads).
            rows.add(rowFor(lead.getId(), lead.getFullName(), memberIds, namesById, today, leaderboardById));
        }
        // Most-at-risk team first: worst delivery success rate, then most overdue leads.
        rows.sort(Comparator
                .comparing((TeamOverviewRow r) -> r.deliverySuccessRate() == null ? 101.0 : r.deliverySuccessRate())
                .thenComparing(Comparator.comparingLong(TeamOverviewRow::dueFollowUps).reversed()));

        List<Long> unassignedIds = allSalespeople.stream()
                .map(User::getId)
                .filter(id -> !assigned.contains(id))
                .toList();
        TeamOverviewRow unassigned = unassignedIds.isEmpty()
                ? null
                : rowFor(null, "Unassigned salespeople", unassignedIds, namesById, today, leaderboardById);

        return new TeamsOverviewResponse(today, rows, unassigned);
    }

    private TeamOverviewRow rowFor(Long teamLeadId, String teamLeadName, List<Long> memberIds,
                                   Map<Long, String> namesById, LocalDate today,
                                   Map<Long, SalespersonPerformanceSummary> leaderboardById) {
        // Orders are scoped to the assigned salespeople PLUS the team lead's own
        // id (the lead punches orders too — their sales count towards the team).
        // Leads stay scoped to the assigned salespeople only (a lead owns no leads).
        List<Long> orderScopeIds = new ArrayList<>(memberIds);
        if (teamLeadId != null && !orderScopeIds.contains(teamLeadId)) {
            orderScopeIds.add(teamLeadId);
        }
        List<OrderEntity> orders = orderScopeIds.isEmpty()
                ? List.of() : safe(orderRepository.findAllScopedIn(orderScopeIds));
        List<LeadEntity> leads = memberIds.isEmpty()
                ? List.of() : safe(leadRepository.findAllScopedIn(memberIds));

        LocalDate monthStart = today.withDayOfMonth(1);
        long ordersTotal = orders.size();
        long ordersThisMonth = 0;
        long delivered = 0;
        long failed = 0;
        BigDecimal revenueTotal = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (OrderEntity order : orders) {
            boolean revenue = !NON_REVENUE.contains(order.getOrderStatus());
            BigDecimal amount = nz(order.getTotalAmount());
            if (revenue) {
                revenueTotal = revenueTotal.add(amount);
            }
            if (order.getCreatedAt() != null && !order.getCreatedAt().toLocalDate().isBefore(monthStart)) {
                ordersThisMonth++;
                if (revenue) {
                    revenueThisMonth = revenueThisMonth.add(amount);
                }
            }
            if (DELIVERED.contains(order.getOrderStatus())) {
                delivered++;
            } else if (FAILED.contains(order.getOrderStatus())) {
                failed++;
            }
            outstanding = outstanding.add(nz(order.getCustomerOutstanding()));
        }
        Double successRate = delivered + failed == 0 ? null : pct(delivered, delivered + failed);

        Map<LeadStatus, Long> pipelineCounts = new EnumMap<>(LeadStatus.class);
        for (LeadStatus s : LeadStatus.values()) {
            pipelineCounts.put(s, 0L);
        }
        long leadsWon = 0;
        long leadsLost = 0;
        List<TeamCallOut> callOuts = new ArrayList<>();
        for (LeadEntity lead : leads) {
            pipelineCounts.merge(lead.getStatus(), 1L, Long::sum);
            if (lead.getStatus() == LeadStatus.WON) {
                leadsWon++;
            } else if (lead.getStatus() == LeadStatus.LOST) {
                leadsLost++;
            }
            if (lead.getFollowUpDate() != null && !lead.getFollowUpDate().isAfter(today)
                    && lead.getStatus() != LeadStatus.WON && lead.getStatus() != LeadStatus.LOST) {
                long overdueDays = Math.max(0, ChronoUnit.DAYS.between(lead.getFollowUpDate(), today));
                callOuts.add(new TeamCallOut(
                        lead.getId(), lead.getCustomerName(), lead.getCustomerMobile(),
                        lead.getLeadSource() == null ? null : lead.getLeadSource().name(),
                        lead.getStatus().name(), lead.getFollowUpDate(), overdueDays,
                        namesById.getOrDefault(lead.getOwnerUserId(), "Unknown")));
            }
        }
        callOuts.sort(Comparator.comparingLong(TeamCallOut::overdueDays).reversed());
        long dueFollowUps = callOuts.size();
        List<TeamCallOut> cappedCallOuts = callOuts.size() > MAX_CALL_OUTS
                ? List.copyOf(callOuts.subList(0, MAX_CALL_OUTS)) : callOuts;

        long leadsTotal = leads.size();
        Double leadConversionRate = leadsTotal == 0 ? null : pct(leadsWon, leadsTotal);

        Map<String, Long> pipelineByName = new LinkedHashMap<>();
        for (LeadStatus s : LeadStatus.values()) {
            pipelineByName.put(s.name(), pipelineCounts.get(s));
        }

        // Slice the pre-computed leaderboard to this team's members, preserving
        // leaderboardFor()'s ordering (this-month revenue desc, then orders desc).
        List<SalespersonPerformanceSummary> members = new ArrayList<>();
        for (Long memberId : memberIds) {
            SalespersonPerformanceSummary s = leaderboardById.get(memberId);
            if (s != null) {
                members.add(s);
            }
        }
        // Add the team lead as a member row so their own sales are visible in the
        // per-person table (the leaderboard only lists SALESPERSON rows, so the
        // lead's figures are computed here from this team's loaded orders).
        if (teamLeadId != null) {
            members.add(leadSelfSummary(teamLeadId,
                    namesById.getOrDefault(teamLeadId, teamLeadName), orders, monthStart));
        }
        members.sort(Comparator
                .comparing(SalespersonPerformanceSummary::revenueThisMonth,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingLong(SalespersonPerformanceSummary::ordersThisMonth).reversed()));

        return new TeamOverviewRow(
                teamLeadId, teamLeadName, memberIds.size(), ordersTotal, ordersThisMonth,
                scale(revenueTotal), scale(revenueThisMonth), delivered, failed, successRate,
                scale(outstanding), leadsTotal, leadsWon, leadsLost, leadConversionRate,
                pipelineByName, dueFollowUps, cappedCallOuts, members);
    }

    /**
     * Builds a leaderboard-style summary row for the team lead's OWN orders
     * (the lead punches orders too). Computed from this team's already-loaded
     * orders filtered to {@code created_by == teamLeadId}, mirroring the revenue
     * / delivered / failed / outstanding definitions used for the team totals.
     */
    private SalespersonPerformanceSummary leadSelfSummary(Long teamLeadId, String name,
                                                          List<OrderEntity> orders, LocalDate monthStart) {
        long ordersTotal = 0;
        long ordersThisMonth = 0;
        long delivered = 0;
        long failed = 0;
        BigDecimal revenueTotal = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            if (!teamLeadId.equals(o.getCreatedBy())) {
                continue;
            }
            ordersTotal++;
            boolean revenue = !NON_REVENUE.contains(o.getOrderStatus());
            BigDecimal amount = nz(o.getTotalAmount());
            if (revenue) {
                revenueTotal = revenueTotal.add(amount);
            }
            if (o.getCreatedAt() != null && !o.getCreatedAt().toLocalDate().isBefore(monthStart)) {
                ordersThisMonth++;
                if (revenue) {
                    revenueThisMonth = revenueThisMonth.add(amount);
                }
            }
            if (DELIVERED.contains(o.getOrderStatus())) {
                delivered++;
            } else if (FAILED.contains(o.getOrderStatus())) {
                failed++;
            }
            outstanding = outstanding.add(nz(o.getCustomerOutstanding()));
        }
        double successRate = delivered + failed == 0 ? 0.0 : pct(delivered, delivered + failed);
        // Suffix the name so the lead is visually distinct from their reports.
        String displayName = (name == null || name.isBlank() ? "Team lead" : name) + " (Team Lead)";
        return new SalespersonPerformanceSummary(
                teamLeadId, null, displayName, true, null,
                ordersTotal, ordersThisMonth, 0L,
                scale(revenueTotal), scale(revenueThisMonth),
                delivered, failed, successRate, scale(outstanding));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static double pct(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
