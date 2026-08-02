package com.shifa.oms.dashboard;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.dashboard.domain.DashboardQueue;
import com.shifa.oms.dashboard.dto.RoleDashboardSummary;
import com.shifa.oms.insights.InsightRepository;
import com.shifa.oms.integration.IntegrationEventRepository;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.shopify.OrderReviewReasonRepository;
import com.shifa.oms.lead.LeadReportAggregator;
import com.shifa.oms.lead.LeadService;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionRow;
import com.shifa.oms.lead.dto.LeadReports.PipelineCount;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.ReceivableEntity;
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
            OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED, OrderStatus.RTO, OrderStatus.REDISPATCH);

    /**
     * How far back the unresolved-failure count looks (Req 14.7). Matched to the health
     * console's retention window so the tile and the list can never disagree.
     */
    private static final long INTEGRATION_RETENTION_DAYS = 30;

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final LeadService leadService;
    private final InsightRepository insightRepository;

    /**
     * Integration health sources, both nullable (spec {@code shopify-quikshipx-order-sync},
     * Req 12.5, 14.7). Only the production constructor supplies them, so every existing test
     * call site keeps working unchanged and simply reports zeros.
     */
    private final IntegrationEventRepository integrationEventRepository;
    private final OrderReviewReasonRepository reviewReasonRepository;

    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RoleDashboardService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                SalespersonScopeResolver scopeResolver,
                                LeadService leadService,
                                InsightRepository insightRepository,
                                IntegrationEventRepository integrationEventRepository,
                                OrderReviewReasonRepository reviewReasonRepository) {
        this(orderRepository, receivableRepository, scopeResolver, leadService, insightRepository,
                integrationEventRepository, reviewReasonRepository, Clock.systemDefaultZone());
    }

    /**
     * Legacy constructor without the integration health sources, retained so existing
     * callers and test doubles compile unchanged. A service built this way reports zero
     * integration failures and an empty review queue.
     */
    public RoleDashboardService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                SalespersonScopeResolver scopeResolver,
                                LeadService leadService,
                                InsightRepository insightRepository) {
        this(orderRepository, receivableRepository, scopeResolver, leadService, insightRepository,
                null, null, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    RoleDashboardService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         SalespersonScopeResolver scopeResolver,
                         LeadService leadService,
                         InsightRepository insightRepository,
                         Clock clock) {
        this(orderRepository, receivableRepository, scopeResolver, leadService, insightRepository,
                null, null, clock);
    }

    private RoleDashboardService(OrderRepository orderRepository,
                                 ReceivableRepository receivableRepository,
                                 SalespersonScopeResolver scopeResolver,
                                 LeadService leadService,
                                 InsightRepository insightRepository,
                                 IntegrationEventRepository integrationEventRepository,
                                 OrderReviewReasonRepository reviewReasonRepository,
                                 Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.scopeResolver = scopeResolver;
        this.leadService = leadService;
        this.insightRepository = insightRepository;
        this.integrationEventRepository = integrationEventRepository;
        this.reviewReasonRepository = reviewReasonRepository;
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
        Long createdBy = scopeResolver.creatorConstraint(principal).orElse(null);
        List<OrderEntity> orders = orderRepository.findAllScoped(createdBy);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderEntity o : orders) {
            String key = o.getOrderStatus() == null ? "" : o.getOrderStatus().name();
            byStatus.merge(key, 1L, Long::sum);
        }
        long awaitingApproval = DashboardQueue.APPROVAL.count(orders, OrderEntity::getOrderStatus);

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
        List<OrderEntity> orders = memberIds.isEmpty()
                ? List.of()
                : orderRepository.findAllScopedIn(memberIds);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderEntity o : orders) {
            String key = o.getOrderStatus() == null ? "" : o.getOrderStatus().name();
            byStatus.merge(key, 1L, Long::sum);
        }
        long awaitingApproval = DashboardQueue.APPROVAL.count(orders, OrderEntity::getOrderStatus);
        return new RoleDashboardSummary.Salesperson(
                byStatus, awaitingApproval, new LinkedHashMap<>(), 0L);
    }

    private RoleDashboardSummary.Admin admin(AuthPrincipal principal) {
        List<OrderEntity> orders = orderRepository.findAll();

        long pendingApproval = DashboardQueue.APPROVAL.count(orders, OrderEntity::getOrderStatus);
        Map<String, Long> perActiveStage = new LinkedHashMap<>();
        for (OrderStatus s : ACTIVE_STAGES) {
            perActiveStage.put(s.name(), 0L);
        }
        Map<String, Long> exceptionStates = new LinkedHashMap<>();
        for (OrderStatus s : EXCEPTION_STATES) {
            exceptionStates.put(s.name(), 0L);
        }
        for (OrderEntity o : orders) {
            OrderStatus s = o.getOrderStatus();
            if (ACTIVE_STAGES.contains(s)) {
                perActiveStage.merge(s.name(), 1L, Long::sum);
            } else if (EXCEPTION_STATES.contains(s)) {
                exceptionStates.merge(s.name(), 1L, Long::sum);
            }
        }
        long awaitingHandover = DashboardQueue.AWAITING_HANDOVER.count(orders, OrderEntity::getOrderStatus);
        long awaitingDispatch = DashboardQueue.AWAITING_DISPATCH.count(orders, OrderEntity::getOrderStatus);
        return new RoleDashboardSummary.Admin(
                pendingApproval, perActiveStage, exceptionStates, awaitingHandover, awaitingDispatch,
                adminLeads(principal), adminInsights(), channels(orders), integrations());
    }

    /**
     * The per-channel order count and revenue (spec {@code shopify-quikshipx-order-sync},
     * Req 12.5).
     *
     * <p>Computed from the orders already loaded for the rest of the admin section, so the
     * split cannot disagree with the totals beside it. Revenue excludes {@code REJECTED} and
     * {@code CANCELLED} orders, matching the rule used by the reports; the order counts do
     * not, so "orders taken" and "revenue earned" stay distinguishable.
     */
    private RoleDashboardSummary.Channels channels(List<OrderEntity> orders) {
        long shopifyOrders = 0;
        long shifaOrders = 0;
        BigDecimal shopifyRevenue = BigDecimal.ZERO;
        BigDecimal shifaRevenue = BigDecimal.ZERO;

        for (OrderEntity o : orders) {
            OrderSource source = o.getSource();
            // A null source predates the Shopify channel entirely, so it is Shifa's.
            boolean shopify = source != null && source.isShopify();
            boolean earnsRevenue = o.getOrderStatus() != OrderStatus.REJECTED
                    && o.getOrderStatus() != OrderStatus.CANCELLED;
            BigDecimal total = o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount();
            if (shopify) {
                shopifyOrders++;
                if (earnsRevenue) {
                    shopifyRevenue = shopifyRevenue.add(total);
                }
            } else {
                shifaOrders++;
                if (earnsRevenue) {
                    shifaRevenue = shifaRevenue.add(total);
                }
            }
        }
        return new RoleDashboardSummary.Channels(
                shopifyOrders, shopifyRevenue, shifaOrders, shifaRevenue);
    }

    /**
     * The integration health headline (spec {@code shopify-quikshipx-order-sync}, Req 14.7).
     *
     * <p>Reports zeros when the integration repositories are absent, which is the case in
     * every pre-existing unit test and in any deployment without the feature — so the tile
     * says "nothing wrong" rather than failing the whole dashboard.
     */
    private RoleDashboardSummary.Integrations integrations() {
        long failures = 0;
        if (integrationEventRepository != null) {
            failures = integrationEventRepository.countByOutcomeInAndReceivedAtGreaterThanEqual(
                    IntegrationOutcome.unresolvedFailures(),
                    LocalDate.now(clock).minusDays(INTEGRATION_RETENTION_DAYS).atStartOfDay());
        }
        long awaitingReview = reviewReasonRepository == null
                ? 0 : reviewReasonRepository.findDistinctOrderIds().size();
        return new RoleDashboardSummary.Integrations(failures, awaitingReview);
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
        List<OrderEntity> orders = orderRepository.findAll();
        long awaitingPacking = DashboardQueue.PACKING.count(orders, OrderEntity::getOrderStatus);
        long awaitingHandover = DashboardQueue.AWAITING_HANDOVER.count(orders, OrderEntity::getOrderStatus);
        long awaitingDispatch = DashboardQueue.AWAITING_DISPATCH.count(orders, OrderEntity::getOrderStatus);
        long packedToday = packedToday(orders);
        return new RoleDashboardSummary.Packing(
                awaitingPacking, packedToday, awaitingHandover, awaitingDispatch);
    }

    private RoleDashboardSummary.Accountant accountant() {
        BigDecimal codPending = BigDecimal.ZERO;
        BigDecimal codSettled = BigDecimal.ZERO;
        for (ReceivableEntity e : receivableRepository
                .findByTypeOrderByCreatedAtDescIdDesc(ReceivableType.COD_RECEIVABLE)) {
            if (e.isSettled()) {
                codSettled = codSettled.add(nz(e.getAmount()));
            } else {
                codPending = codPending.add(nz(e.getAmount()));
            }
        }
        BigDecimal outstanding = codPending.add(unsettledTotal(ReceivableType.CLAIM_RECEIVABLE));
        return new RoleDashboardSummary.Accountant(
                scale(codPending), scale(codSettled), scale(outstanding));
    }

    // --- Helpers ------------------------------------------------------------

    /** Count of orders that reached {@code PACKED} (or beyond handover) today. */
    private long packedToday(List<OrderEntity> orders) {
        LocalDate today = LocalDate.now(clock);
        long count = 0;
        for (OrderEntity o : orders) {
            if (o.getOrderStatus() == OrderStatus.PACKED
                    && o.getUpdatedAt() != null
                    && o.getUpdatedAt().toLocalDate().equals(today)) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal unsettledTotal(ReceivableType type) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceivableEntity e : receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(type)) {
            total = total.add(nz(e.getAmount()));
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
