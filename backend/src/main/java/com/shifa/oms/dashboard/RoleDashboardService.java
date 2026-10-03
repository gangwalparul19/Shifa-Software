package com.shifa.oms.dashboard;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.dashboard.domain.DashboardQueue;
import com.shifa.oms.dashboard.dto.RoleDashboardSummary;
import com.shifa.oms.insights.InsightRepository;
import com.shifa.oms.lead.LeadReportAggregator;
import com.shifa.oms.lead.LeadService;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionRow;
import com.shifa.oms.lead.dto.LeadReports.PipelineCount;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the role-shaped dashboard summary served by
 * {@link RoleDashboardController} (design §6.7, Req 3.1&ndash;3.6).
 *
 * <p>The payload is assembled entirely from the caller's {@link AuthPrincipal}:
 * a {@code SALESPERSON} sees only their own orders (scoped through
 * {@link SalespersonScopeResolver}, Req 3.2, 2.6); {@code ADMIN},
 * {@code PACKING_USER}, and {@code ACCOUNTANT} see operation-wide figures.
 * Queue membership is delegated to the pure {@link DashboardQueue} classifier so
 * the queues shown here contain exactly the orders in their status set
 * (Property 13).
 */
@Service
public class RoleDashboardService {

    /** Statuses treated as "active fulfilment stages" for the admin overview (Req 3.3). */
    private static final Set<OrderStatus> ACTIVE_STAGES = EnumSet.of(
            OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED, OrderStatus.PACKED,
            OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    /** Terminal/exception outcomes surfaced as their own counts for the admin overview (Req 3.3). */
    private static final Set<OrderStatus> EXCEPTION_STATES = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED, OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED, OrderStatus.RTO, OrderStatus.REDISPATCH);

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final LeadService leadService;
    private final InsightRepository insightRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RoleDashboardService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                SalespersonScopeResolver scopeResolver,
                                LeadService leadService,
                                InsightRepository insightRepository) {
        this(orderRepository, receivableRepository, scopeResolver, leadService, insightRepository,
                Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    RoleDashboardService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         SalespersonScopeResolver scopeResolver,
                         LeadService leadService,
                         InsightRepository insightRepository,
                         Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.scopeResolver = scopeResolver;
        this.leadService = leadService;
        this.insightRepository = insightRepository;
        this.clock = clock;
    }

    /** The role-shaped dashboard summary for the authenticated caller (Req 3.1&ndash;3.6). */
    @Transactional(readOnly = true)
    public RoleDashboardSummary summary(AuthPrincipal principal) {
        Role role = principal.role();
        return switch (role) {
            case SALESPERSON -> new RoleDashboardSummary(
                    role.name(), salesperson(principal), null, null, null);
            case TEAM_LEAD -> new RoleDashboardSummary(
                    role.name(), teamLead(principal), null, null, null);
            case ADMIN -> new RoleDashboardSummary(
                    role.name(), null, admin(principal), null, null);
            case PACKING_USER -> new RoleDashboardSummary(
                    role.name(), null, null, packing(), null);
            case ACCOUNTANT -> new RoleDashboardSummary(
                    role.name(), null, null, null, accountant());
            default -> new RoleDashboardSummary(role.name(), null, null, null, null);
        };
    }

    // --- Per-role sections --------------------------------------------------

    private RoleDashboardSummary.Salesperson salesperson(AuthPrincipal principal) {
        // Scope to the caller's own orders (Req 3.2, 2.6): a salesperson's
        // creatorConstraint is their own id, applied at the repository layer.
        // Count statuses in SQL (GROUP BY) rather than loading every order.
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        Map<OrderStatus, Long> counts =
                toStatusMap(orderRepository.statusCountsForCreator(createdBy));

        Map<String, Long> byStatus = byStatusNames(counts);
        long awaitingApproval = DashboardQueue.APPROVAL.countFrom(counts);

        // Lead pipeline-by-stage counts + due-follow-up count for the caller (Req 6.6).
        Map<String, Long> leadPipeline = new LinkedHashMap<>();
        for (Map.Entry<LeadStatus, Long> e : leadService.pipelineCounts(principal).entrySet()) {
            leadPipeline.put(e.getKey().name(), e.getValue());
        }
        long dueFollowUps = leadService.dueFollowUps(principal).size();
        return new RoleDashboardSummary.Salesperson(
                byStatus, awaitingApproval, leadPipeline, dueFollowUps);
    }

    /**
     * Team-lead dashboard: the same order-status breakdown + awaiting-approval
     * count as a salesperson, but aggregated over the orders punched by ALL the
     * salespeople assigned to this lead (team scope). Reuses the Salesperson
     * section shape (no DTO change); lead-pipeline figures are left empty as
     * team-scoped lead reporting is not part of this role yet.
     */
    private RoleDashboardSummary.Salesperson teamLead(AuthPrincipal principal) {
        List<Long> memberIds = scopeResolver.creatorScope(principal).orElse(List.of());
        // Empty team → scoped to nothing (never all); otherwise SQL-count the
        // team's orders by status rather than loading them.
        Map<OrderStatus, Long> counts = memberIds.isEmpty()
                ? Map.of()
                : toStatusMap(orderRepository.statusCountsForCreatorIn(memberIds));

        Map<String, Long> byStatus = byStatusNames(counts);
        long awaitingApproval = DashboardQueue.APPROVAL.countFrom(counts);
        return new RoleDashboardSummary.Salesperson(
                byStatus, awaitingApproval, new LinkedHashMap<>(), 0L);
    }

    private RoleDashboardSummary.Admin admin(AuthPrincipal principal) {
        // Count orders by status in SQL (GROUP BY) rather than loading every order
        // and tallying in Java — same figures, a single aggregate query.
        Map<OrderStatus, Long> counts = toStatusMap(orderRepository.statusCounts());

        long pendingApproval = DashboardQueue.APPROVAL.countFrom(counts);
        Map<String, Long> perActiveStage = new LinkedHashMap<>();
        for (OrderStatus s : ACTIVE_STAGES) {
            perActiveStage.put(s.name(), counts.getOrDefault(s, 0L));
        }
        Map<String, Long> exceptionStates = new LinkedHashMap<>();
        for (OrderStatus s : EXCEPTION_STATES) {
            exceptionStates.put(s.name(), counts.getOrDefault(s, 0L));
        }
        long awaitingHandover = DashboardQueue.AWAITING_HANDOVER.countFrom(counts);
        long awaitingDispatch = DashboardQueue.AWAITING_DISPATCH.countFrom(counts);
        return new RoleDashboardSummary.Admin(
                pendingApproval, perActiveStage, exceptionStates, awaitingHandover, awaitingDispatch,
                adminLeads(principal), adminInsights());
    }

    /**
     * The admin statistical-insights overview (statistical-insights-engine, Req
     * 11.1, 11.2): counts the latest computed date's non-dismissed insights by
     * severity and lists the top few headlines (DANGER→WARNING→INFO, newest id
     * first within a severity). Empty counts + list when nothing has been
     * computed yet.
     */
    private RoleDashboardSummary.Insights adminInsights() {
        java.util.Optional<LocalDate> latest = insightRepository.findMaxComputedDate();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("INFO", 0L);
        counts.put("WARNING", 0L);
        counts.put("DANGER", 0L);
        if (latest.isEmpty()) {
            return new RoleDashboardSummary.Insights(counts, List.of());
        }
        List<com.shifa.oms.insights.InsightEntity> rows =
                insightRepository.findByComputedDateAndDismissedFalse(latest.get());
        for (com.shifa.oms.insights.InsightEntity e : rows) {
            if (e.getSeverity() != null) {
                counts.merge(e.getSeverity().name(), 1L, Long::sum);
            }
        }
        List<com.shifa.oms.insights.InsightEntity> sorted = new java.util.ArrayList<>(rows);
        sorted.sort(java.util.Comparator
                .comparingInt((com.shifa.oms.insights.InsightEntity e) -> severityRank(e.getSeverity()))
                .thenComparing(com.shifa.oms.insights.InsightEntity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        List<RoleDashboardSummary.InsightHeadline> top = new java.util.ArrayList<>();
        for (com.shifa.oms.insights.InsightEntity e : sorted) {
            if (top.size() >= 5) {
                break;
            }
            top.add(new RoleDashboardSummary.InsightHeadline(
                    e.getInsightType() == null ? null : e.getInsightType().name(),
                    e.getSeverity() == null ? null : e.getSeverity().name(),
                    e.getTitle()));
        }
        return new RoleDashboardSummary.Insights(counts, top);
    }

    /** Orders severities DANGER(0) → WARNING(1) → INFO(2) for the top-headlines list. */
    private static int severityRank(com.shifa.oms.insights.domain.InsightSeverity severity) {
        if (severity == null) {
            return 3;
        }
        return switch (severity) {
            case DANGER -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        };
    }

    /**
     * The admin leads/conversion overview (Req 6.6): overall total/won/rate
     * (summed from the unscoped conversion report) plus the current
     * pipeline-by-stage counts. Reuses the pure {@link LeadReportAggregator}
     * groupings via {@link LeadService} so the dashboard and the reports agree.
     */
    private RoleDashboardSummary.Leads adminLeads(AuthPrincipal principal) {
        ConversionReport conversion = leadService.reportConversion(null, null, principal);
        long totalLeads = 0;
        long won = 0;
        for (ConversionRow row : conversion.bySource()) {
            totalLeads += row.leads();
            won += row.won();
        }
        PipelineReport pipeline = leadService.reportPipeline(principal);
        Map<String, Long> pipelineByStage = new LinkedHashMap<>();
        for (PipelineCount row : pipeline.rows()) {
            pipelineByStage.put(row.status().name(), row.count());
        }
        return new RoleDashboardSummary.Leads(
                totalLeads, won, LeadReportAggregator.conversionRate(won, totalLeads), pipelineByStage);
    }

    private RoleDashboardSummary.Packing packing() {
        // SQL GROUP BY for the queue tallies + a dedicated count for "packed today"
        // (no findAll).
        Map<OrderStatus, Long> counts = toStatusMap(orderRepository.statusCounts());
        long awaitingPacking = DashboardQueue.PACKING.countFrom(counts);
        long awaitingHandover = DashboardQueue.AWAITING_HANDOVER.countFrom(counts);
        long awaitingDispatch = DashboardQueue.AWAITING_DISPATCH.countFrom(counts);
        long packedToday = packedToday();
        return new RoleDashboardSummary.Packing(
                awaitingPacking, packedToday, awaitingHandover, awaitingDispatch, packedPerHour(packedToday));
    }

    /**
     * Packing throughput (packing-throughput enhancement): today's packed count
     * over the hours elapsed so far today (minimum 1h so an early-morning burst
     * isn't divided by a fraction), rounded to 1 dp. A floor-productivity signal.
     */
    private double packedPerHour(long packedToday) {
        if (packedToday <= 0) {
            return 0.0;
        }
        int hour = java.time.LocalTime.now(clock).getHour();
        double hoursElapsed = Math.max(1, hour); // 0..23 → at least 1
        return java.math.BigDecimal.valueOf(packedToday / hoursElapsed)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private RoleDashboardSummary.Accountant accountant() {
        // SQL SUM grouped by settlement status (V71 (type, settled) index) rather
        // than loading every receivable row and summing in Java.
        BigDecimal codPending = nz(receivableRepository
                .sumAmountByTypeAndSettled(ReceivableType.COD_RECEIVABLE, false));
        BigDecimal codSettled = nz(receivableRepository
                .sumAmountByTypeAndSettled(ReceivableType.COD_RECEIVABLE, true));
        BigDecimal outstanding = codPending.add(unsettledTotal(ReceivableType.CLAIM_RECEIVABLE));
        return new RoleDashboardSummary.Accountant(
                scale(codPending), scale(codSettled), scale(outstanding));
    }

    // --- Helpers ------------------------------------------------------------

    /**
     * Count of orders currently in {@code PACKED} last updated today, counted in
     * SQL over the clock's calendar day {@code [startOfDay, startOfNextDay)} —
     * identical to the previous in-memory {@code updatedAt.toLocalDate() == today}
     * check, but without loading every order.
     */
    private long packedToday() {
        LocalDate today = LocalDate.now(clock);
        return orderRepository.countPackedBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    /**
     * Folds a {@link OrderRepository.StatusCountRow} list into a map keyed by the
     * parsed {@link OrderStatus}. Any unrecognised status name (defensive — the
     * column is a known enum) is skipped so a stray value can never break the
     * dashboard.
     */
    private static Map<OrderStatus, Long> toStatusMap(List<OrderRepository.StatusCountRow> rows) {
        Map<OrderStatus, Long> counts = new java.util.EnumMap<>(OrderStatus.class);
        for (OrderRepository.StatusCountRow row : rows) {
            if (row.getStatus() == null) {
                continue;
            }
            try {
                counts.merge(OrderStatus.valueOf(row.getStatus()), row.getCount(), Long::sum);
            } catch (IllegalArgumentException ignored) {
                // Unknown status name — skip (never happens for a valid enum column).
            }
        }
        return counts;
    }

    /**
     * The order-status breakdown keyed by status name, in lifecycle (enum
     * declaration) order, including only statuses that have at least one order —
     * matching the shape the salesperson/team-lead sections previously built from
     * the loaded orders.
     */
    private static Map<String, Long> byStatusNames(Map<OrderStatus, Long> counts) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            Long c = counts.get(status);
            if (c != null && c > 0) {
                byStatus.put(status.name(), c);
            }
        }
        return byStatus;
    }

    private BigDecimal unsettledTotal(ReceivableType type) {
        // SQL SUM of unsettled amounts (V71 (type, settled) index).
        return nz(receivableRepository.sumAmountByTypeAndSettled(type, false));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
