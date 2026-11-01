package com.shifa.oms.dashboard;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.TeamsOverviewQueryRepository.CreatorStatusRow;
import com.shifa.oms.dashboard.TeamsOverviewQueryRepository.DueLeadRow;
import com.shifa.oms.dashboard.TeamsOverviewQueryRepository.OwnerCountRow;
import com.shifa.oms.dashboard.TeamsOverviewQueryRepository.OwnerLeadStatusRow;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamCallOut;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamOverviewRow;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.performance.SalespersonPerformanceService;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *
 * <p>All order and lead figures come from grouped SQL aggregates
 * ({@link TeamsOverviewQueryRepository}): orders per creator and status
 * (lifetime and this month), leads per owner and status, the number of due
 * follow-ups, and only the (capped) due follow-up leads themselves for the
 * call-out list. Nothing loads whole order or lead tables.
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
    private final TeamsOverviewQueryRepository queryRepository;
    private final SalespersonPerformanceService performanceService;
    private final Clock clock;

    @Autowired
    public TeamsOverviewService(UserRepository userRepository,
                                TeamsOverviewQueryRepository queryRepository,
                                SalespersonPerformanceService performanceService) {
        this(userRepository, queryRepository, performanceService, Clock.system(BUSINESS_ZONE));
    }

    /** Test constructor with a fixed clock. */
    TeamsOverviewService(UserRepository userRepository,
                         TeamsOverviewQueryRepository queryRepository,
                         SalespersonPerformanceService performanceService,
                         Clock clock) {
        this.userRepository = userRepository;
        this.queryRepository = queryRepository;
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

        // Resolve every team's roster first so ALL order/lead figures can be read
        // with one grouped query each, then sliced per team below.
        Map<Long, List<Long>> membersByLead = new LinkedHashMap<>();
        Set<Long> assigned = new HashSet<>();
        for (User lead : leads) {
            List<Long> memberIds = userRepository.findIdsByTeamLeadId(lead.getId());
            membersByLead.put(lead.getId(), memberIds);
            assigned.addAll(memberIds);
        }
        List<Long> unassignedIds = allSalespeople.stream()
                .map(User::getId)
                .filter(id -> !assigned.contains(id))
                .toList();

        // Orders are scoped to the assigned salespeople PLUS the team lead's own
        // id (the lead punches orders too — their sales count towards the team).
        // Leads stay scoped to the assigned salespeople only (a lead owns no leads).
        Set<Long> creatorIds = new LinkedHashSet<>();
        Set<Long> leadOwnerIds = new LinkedHashSet<>();
        for (User lead : leads) {
            List<Long> memberIds = membersByLead.get(lead.getId());
            creatorIds.addAll(memberIds);
            creatorIds.add(lead.getId());
            leadOwnerIds.addAll(memberIds);
        }
        creatorIds.addAll(unassignedIds);
        leadOwnerIds.addAll(unassignedIds);

        LocalDate monthStart = today.withDayOfMonth(1);
        Aggregates aggregates = new Aggregates(
                orderTotalsByCreator(creatorIds, monthStart),
                leadStatusCountsByOwner(leadOwnerIds),
                dueFollowUpCountsByOwner(leadOwnerIds, today));

        List<TeamOverviewRow> rows = new ArrayList<>();
        for (User lead : leads) {
            // The team lead now also punches orders (team-lead order entry), so a
            // team's sales must INCLUDE the lead's own orders. Pass the lead id so
            // rowFor folds the lead's own orders into every order-derived total and
            // shows the lead as a member row — while the assigned-salespeople set
            // still drives the team size and the lead pipeline (a lead owns no leads).
            rows.add(rowFor(lead.getId(), lead.getFullName(), membersByLead.get(lead.getId()),
                    namesById, today, leaderboardById, aggregates));
        }
        // Most-at-risk team first: worst delivery success rate, then most overdue leads.
        rows.sort(Comparator
                .comparing((TeamOverviewRow r) -> r.deliverySuccessRate() == null ? 101.0 : r.deliverySuccessRate())
                .thenComparing(Comparator.comparingLong(TeamOverviewRow::dueFollowUps).reversed()));

        TeamOverviewRow unassigned = unassignedIds.isEmpty()
                ? null
                : rowFor(null, "Unassigned salespeople", unassignedIds, namesById, today, leaderboardById,
                        aggregates);

        return new TeamsOverviewResponse(today, rows, unassigned);
    }

    private TeamOverviewRow rowFor(Long teamLeadId, String teamLeadName, List<Long> memberIds,
                                   Map<Long, String> namesById, LocalDate today,
                                   Map<Long, SalespersonPerformanceSummary> leaderboardById,
                                   Aggregates aggregates) {
        // Orders: the assigned salespeople PLUS the team lead's own id. Each person
        // is counted once, exactly as the old IN (...) query counted each order once.
        Set<Long> orderScopeIds = new LinkedHashSet<>(memberIds);
        if (teamLeadId != null) {
            orderScopeIds.add(teamLeadId);
        }
        OrderTotals team = new OrderTotals();
        for (Long id : orderScopeIds) {
            OrderTotals person = aggregates.ordersByPerson().get(id);
            if (person != null) {
                team.add(person);
            }
        }
        long ordersTotal = team.ordersTotal;
        long ordersThisMonth = team.ordersThisMonth;
        long delivered = team.delivered;
        long failed = team.failed;
        BigDecimal revenueTotal = team.revenueTotal;
        BigDecimal revenueThisMonth = team.revenueThisMonth;
        BigDecimal outstanding = team.outstanding;
        Double successRate = delivered + failed == 0 ? null : pct(delivered, delivered + failed);

        // Leads: the assigned salespeople only.
        Set<Long> leadOwnerIds = new LinkedHashSet<>(memberIds);
        Map<LeadStatus, Long> pipelineCounts = new EnumMap<>(LeadStatus.class);
        for (LeadStatus s : LeadStatus.values()) {
            pipelineCounts.put(s, 0L);
        }
        long dueFollowUps = 0;
        for (Long ownerId : leadOwnerIds) {
            Map<LeadStatus, Long> perStatus = aggregates.leadsByOwner().get(ownerId);
            if (perStatus != null) {
                perStatus.forEach((status, count) -> pipelineCounts.merge(status, count, Long::sum));
            }
            dueFollowUps += aggregates.dueByOwner().getOrDefault(ownerId, 0L);
        }
        long leadsTotal = 0;
        for (long count : pipelineCounts.values()) {
            leadsTotal += count;
        }
        long leadsWon = pipelineCounts.get(LeadStatus.WON);
        long leadsLost = pipelineCounts.get(LeadStatus.LOST);

        List<TeamCallOut> callOuts = dueFollowUps == 0 || leadOwnerIds.isEmpty()
                ? List.of()
                : callOutsFor(leadOwnerIds, today, namesById);

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
        // lead's figures come from the per-creator order aggregates).
        if (teamLeadId != null) {
            OrderTotals own = aggregates.ordersByPerson().get(teamLeadId);
            members.add(leadSelfSummary(teamLeadId,
                    namesById.getOrDefault(teamLeadId, teamLeadName),
                    own == null ? new OrderTotals() : own));
        }
        members.sort(Comparator
                .comparing(SalespersonPerformanceSummary::revenueThisMonth,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparingLong(SalespersonPerformanceSummary::ordersThisMonth).reversed()));

        return new TeamOverviewRow(
                teamLeadId, teamLeadName, memberIds.size(), ordersTotal, ordersThisMonth,
                scale(revenueTotal), scale(revenueThisMonth), delivered, failed, successRate,
                scale(outstanding), leadsTotal, leadsWon, leadsLost, leadConversionRate,
                pipelineByName, dueFollowUps, callOuts, members);
    }

    /** The most-overdue open leads for a team, capped at {@link #MAX_CALL_OUTS}. */
    private List<TeamCallOut> callOutsFor(Collection<Long> ownerIds, LocalDate today,
                                          Map<Long, String> namesById) {
        List<DueLeadRow> due = queryRepository.dueFollowUps(ownerIds, today, PageRequest.of(0, MAX_CALL_OUTS));
        List<TeamCallOut> callOuts = new ArrayList<>();
        if (due == null) {
            return callOuts;
        }
        for (DueLeadRow lead : due) {
            long overdueDays = Math.max(0, ChronoUnit.DAYS.between(lead.getFollowUpDate(), today));
            callOuts.add(new TeamCallOut(
                    lead.getLeadId(), lead.getCustomerName(), lead.getCustomerMobile(),
                    lead.getLeadSource() == null ? null : lead.getLeadSource().name(),
                    lead.getLeadStatus().name(), lead.getFollowUpDate(), overdueDays,
                    namesById.getOrDefault(lead.getOwnerId(), "Unknown")));
            if (callOuts.size() == MAX_CALL_OUTS) {
                break;
            }
        }
        return callOuts;
    }

    /**
     * Builds a leaderboard-style summary row for the team lead's OWN orders
     * (the lead punches orders too). Uses the lead's per-creator order totals,
     * mirroring the revenue / delivered / failed / outstanding definitions used
     * for the team totals.
     */
    private SalespersonPerformanceSummary leadSelfSummary(Long teamLeadId, String name, OrderTotals own) {
        double successRate = own.delivered + own.failed == 0 ? 0.0 : pct(own.delivered, own.delivered + own.failed);
        // Suffix the name so the lead is visually distinct from their reports.
        String displayName = (name == null || name.isBlank() ? "Team lead" : name) + " (Team Lead)";
        return new SalespersonPerformanceSummary(
                teamLeadId, null, displayName, true, null,
                own.ordersTotal, own.ordersThisMonth, 0L,
                scale(own.revenueTotal), scale(own.revenueThisMonth),
                own.delivered, own.failed, successRate, scale(own.outstanding));
    }

    // --- Aggregate loading ----------------------------------------------------

    /** Lifetime + this-month order figures per creator id (one grouped query each). */
    private Map<Long, OrderTotals> orderTotalsByCreator(Collection<Long> creatorIds, LocalDate monthStart) {
        Map<Long, OrderTotals> byPerson = new HashMap<>();
        if (creatorIds.isEmpty()) {
            return byPerson;
        }
        List<CreatorStatusRow> lifetime = queryRepository.orderTotalsByCreator(creatorIds);
        if (lifetime != null) {
            for (CreatorStatusRow row : lifetime) {
                if (row.getPersonId() != null) {
                    byPerson.computeIfAbsent(row.getPersonId(), k -> new OrderTotals()).addLifetime(row);
                }
            }
        }
        List<CreatorStatusRow> thisMonth = queryRepository.orderTotalsByCreatorSince(
                creatorIds, monthStart.atStartOfDay());
        if (thisMonth != null) {
            for (CreatorStatusRow row : thisMonth) {
                if (row.getPersonId() != null) {
                    byPerson.computeIfAbsent(row.getPersonId(), k -> new OrderTotals()).addThisMonth(row);
                }
            }
        }
        return byPerson;
    }

    /** Lead counts per owner id and status. */
    private Map<Long, Map<LeadStatus, Long>> leadStatusCountsByOwner(Collection<Long> ownerIds) {
        Map<Long, Map<LeadStatus, Long>> byOwner = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return byOwner;
        }
        List<OwnerLeadStatusRow> rows = queryRepository.leadCountsByOwnerAndStatus(ownerIds);
        if (rows != null) {
            for (OwnerLeadStatusRow row : rows) {
                if (row.getOwnerId() != null && row.getLeadStatus() != null) {
                    byOwner.computeIfAbsent(row.getOwnerId(), k -> new EnumMap<>(LeadStatus.class))
                            .merge(row.getLeadStatus(), nzLong(row.getLeadCount()), Long::sum);
                }
            }
        }
        return byOwner;
    }

    /** Open leads due or overdue for a follow-up today, per owner id. */
    private Map<Long, Long> dueFollowUpCountsByOwner(Collection<Long> ownerIds, LocalDate today) {
        Map<Long, Long> byOwner = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return byOwner;
        }
        List<OwnerCountRow> rows = queryRepository.dueFollowUpCountsByOwner(ownerIds, today);
        if (rows != null) {
            for (OwnerCountRow row : rows) {
                if (row.getOwnerId() != null) {
                    byOwner.merge(row.getOwnerId(), nzLong(row.getLeadCount()), Long::sum);
                }
            }
        }
        return byOwner;
    }

    private record Aggregates(Map<Long, OrderTotals> ordersByPerson,
                              Map<Long, Map<LeadStatus, Long>> leadsByOwner,
                              Map<Long, Long> dueByOwner) {
    }

    /** Mutable order rollup for one creator, or for a whole team once added up. */
    private static final class OrderTotals {
        private long ordersTotal;
        private long ordersThisMonth;
        private long delivered;
        private long failed;
        private BigDecimal revenueTotal = BigDecimal.ZERO;
        private BigDecimal revenueThisMonth = BigDecimal.ZERO;
        private BigDecimal outstanding = BigDecimal.ZERO;

        void addLifetime(CreatorStatusRow row) {
            OrderStatus status = row.getOrderStatus();
            long count = nzLong(row.getOrderCount());
            ordersTotal += count;
            if (!NON_REVENUE.contains(status)) {
                revenueTotal = revenueTotal.add(nz(row.getTotalAmount()));
            }
            if (DELIVERED.contains(status)) {
                delivered += count;
            } else if (FAILED.contains(status)) {
                failed += count;
            }
            // Customer-outstanding covers every order in scope, whatever its status.
            outstanding = outstanding.add(nz(row.getOutstandingAmount()));
        }

        void addThisMonth(CreatorStatusRow row) {
            ordersThisMonth += nzLong(row.getOrderCount());
            if (!NON_REVENUE.contains(row.getOrderStatus())) {
                revenueThisMonth = revenueThisMonth.add(nz(row.getTotalAmount()));
            }
        }

        void add(OrderTotals other) {
            ordersTotal += other.ordersTotal;
            ordersThisMonth += other.ordersThisMonth;
            delivered += other.delivered;
            failed += other.failed;
            revenueTotal = revenueTotal.add(other.revenueTotal);
            revenueThisMonth = revenueThisMonth.add(other.revenueThisMonth);
            outstanding = outstanding.add(other.outstanding);
        }
    }

    private static long nzLong(Long value) {
        return value == null ? 0L : value;
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
