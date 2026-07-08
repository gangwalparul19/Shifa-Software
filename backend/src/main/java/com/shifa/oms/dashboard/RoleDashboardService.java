package com.shifa.oms.dashboard;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.dashboard.domain.DashboardQueue;
import com.shifa.oms.dashboard.dto.RoleDashboardSummary;
import com.shifa.oms.lead.LeadReportAggregator;
import com.shifa.oms.lead.LeadService;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.dto.LeadReports.ConversionReport;
import com.shifa.oms.lead.dto.LeadReports.ConversionRow;
import com.shifa.oms.lead.dto.LeadReports.PipelineCount;
import com.shifa.oms.lead.dto.LeadReports.PipelineReport;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
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
            OrderStatus.DELIVERY_FAILED, OrderStatus.RTO, OrderStatus.COURIER_LOST);

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final LeadService leadService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RoleDashboardService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                SalespersonScopeResolver scopeResolver,
                                LeadService leadService) {
        this(orderRepository, receivableRepository, scopeResolver, leadService,
                Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    RoleDashboardService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         SalespersonScopeResolver scopeResolver,
                         LeadService leadService,
                         Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.scopeResolver = scopeResolver;
        this.leadService = leadService;
        this.clock = clock;
    }

    /** The role-shaped dashboard summary for the authenticated caller (Req 3.1&ndash;3.6). */
    @Transactional(readOnly = true)
    public RoleDashboardSummary summary(AuthPrincipal principal) {
        Role role = principal.role();
        return switch (role) {
            case SALESPERSON -> new RoleDashboardSummary(
                    role.name(), salesperson(principal), null, null, null);
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
                adminLeads(principal));
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
