package com.shifa.oms.insights;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.insights.domain.CodOutstanding;
import com.shifa.oms.insights.domain.CourierOutcome;
import com.shifa.oms.insights.domain.Insight;
import com.shifa.oms.insights.domain.InsightEngine;
import com.shifa.oms.insights.domain.InsightInputs;
import com.shifa.oms.insights.domain.InsightThresholds;
import com.shifa.oms.insights.domain.LeadSourceConversion;
import com.shifa.oms.insights.domain.OpenOrderRisk;
import com.shifa.oms.insights.domain.ProductConsumption;
import com.shifa.oms.insights.domain.ReturnStats;
import com.shifa.oms.insights.domain.SalesWindow;
import com.shifa.oms.inventory.StockMovement;
import com.shifa.oms.inventory.StockMovementRepository;
import com.shifa.oms.inventory.StockMovementType;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.returns.OrderReturnRepository;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Gathers data from the existing repositories, runs the pure
 * {@link InsightEngine}, and persists the result idempotently per computed date
 * (statistical-insights-engine, design §Services). The nightly
 * {@link InsightNightlyJob} and the admin {@code POST /api/insights/recompute}
 * both drive {@link #computeForToday()}.
 *
 * <p>The service is intentionally the only impure part of the pipeline: it reads
 * orders, stock movements, courier records, returns, receivables, and leads into
 * pure projection records, hands them to the stateless engine, then
 * {@code deleteByComputedDate(today) + saveAll} so a same-date rerun replaces
 * (never duplicates) that date's insights (Req 1.2). Every insight family gathers
 * independently inside a guarded block so one failing family is logged and
 * skipped rather than aborting the whole run (Req 1.3).
 *
 * <p>Notifiable (WARNING/DANGER) insights fan out to admins via the transactional
 * outbox + {@link StaffNotificationDispatcher} exactly once per natural key: the
 * set of already-notifiable insights for {@code today} is captured <em>before</em>
 * the delete, so re-running a day never raises a duplicate admin notification
 * (Req 10.1). An audit event records each run (Req 12.2).
 *
 * <p>Dual-constructor {@link Clock} (mirrors {@code RoleDashboardService} /
 * {@code FollowUpReminderJob}): the primary {@code @Autowired} constructor binds
 * {@code app.insights.*} into an {@link InsightThresholds} and uses the system
 * clock; the wider constructor lets tests inject a fixed clock and explicit
 * thresholds.
 */
@Service
public class InsightComputationService {

    private static final Logger log = LoggerFactory.getLogger(InsightComputationService.class);

    /** Staff-notification type discriminator for a notifiable insight. */
    static final String NOTIFICATION_TYPE = "INSIGHT_ALERT";

    /**
     * Open (non-terminal, non-approval) fulfilment stages an order can be in when
     * it is a candidate for an RTO-risk insight (design §Services). Mirrors the
     * dashboard's active-stage set.
     */
    private static final Set<OrderStatus> OPEN_STATES = EnumSet.of(
            OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED, OrderStatus.PACKED,
            OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    /** Terminal delivery-failure outcomes used for the historical state failure rate + prior-failed count. */
    private static final Set<OrderStatus> FAILED_TERMINAL = EnumSet.of(
            OrderStatus.RTO, OrderStatus.DELIVERY_FAILED, OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.COURIER_LOST);

    /** Terminal statuses that count as a successful delivery for scorecards/returns. */
    private static final Set<OrderStatus> DELIVERED_TERMINAL = EnumSet.of(
            OrderStatus.DELIVERED, OrderStatus.CLOSED, OrderStatus.COD_COLLECTED);

    private final InsightEngine engine = new InsightEngine();

    private final InsightRepository insightRepository;
    private final OrderRepository orderRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final ReceivableRepository receivableRepository;
    private final LeadRepository leadRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final StaffNotificationDispatcher staffNotificationDispatcher;
    private final AuditService auditService;
    private final InsightThresholds thresholds;
    private final int windowDays;
    private final Clock clock;

    /** Production constructor (Spring): binds {@code app.insights.*} and uses the system clock. */
    @Autowired
    public InsightComputationService(
            InsightRepository insightRepository,
            OrderRepository orderRepository,
            StockMovementRepository stockMovementRepository,
            ProductRepository productRepository,
            CourierRecordRepository courierRecordRepository,
            CourierCompanyRepository courierCompanyRepository,
            OrderReturnRepository orderReturnRepository,
            ReceivableRepository receivableRepository,
            LeadRepository leadRepository,
            OutboxEventPublisher outboxEventPublisher,
            StaffNotificationDispatcher staffNotificationDispatcher,
            AuditService auditService,
            @Value("${app.insights.window-days:7}") int windowDays,
            @Value("${app.insights.sales-anomaly-pct:30}") BigDecimal salesAnomalyPct,
            @Value("${app.insights.reorder-lookback-days:30}") int reorderLookbackDays,
            @Value("${app.insights.reorder-cover-days:14}") int reorderCoverDays,
            @Value("${app.insights.rto-risk-threshold:60}") int rtoRiskThreshold,
            @Value("${app.insights.courier-rto-warn-pct:15}") BigDecimal courierRtoWarnPct,
            @Value("${app.insights.return-rate-warn-pct:10}") BigDecimal returnRateWarnPct,
            @Value("${app.insights.cod-outstanding-warn:50000}") BigDecimal codOutstandingWarn) {
        this(insightRepository, orderRepository, stockMovementRepository, productRepository,
                courierRecordRepository, courierCompanyRepository, orderReturnRepository,
                receivableRepository, leadRepository, outboxEventPublisher,
                staffNotificationDispatcher, auditService,
                new InsightThresholds(salesAnomalyPct, reorderLookbackDays, reorderCoverDays,
                        rtoRiskThreshold, courierRtoWarnPct, returnRateWarnPct, codOutstandingWarn),
                windowDays, Clock.systemDefaultZone());
    }

    /** Wider constructor allowing explicit thresholds + a fixed clock in tests. */
    public InsightComputationService(
            InsightRepository insightRepository,
            OrderRepository orderRepository,
            StockMovementRepository stockMovementRepository,
            ProductRepository productRepository,
            CourierRecordRepository courierRecordRepository,
            CourierCompanyRepository courierCompanyRepository,
            OrderReturnRepository orderReturnRepository,
            ReceivableRepository receivableRepository,
            LeadRepository leadRepository,
            OutboxEventPublisher outboxEventPublisher,
            StaffNotificationDispatcher staffNotificationDispatcher,
            AuditService auditService,
            InsightThresholds thresholds,
            int windowDays,
            Clock clock) {
        this.insightRepository = Objects.requireNonNull(insightRepository, "insightRepository");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.stockMovementRepository =
                Objects.requireNonNull(stockMovementRepository, "stockMovementRepository");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository");
        this.courierRecordRepository =
                Objects.requireNonNull(courierRecordRepository, "courierRecordRepository");
        this.courierCompanyRepository =
                Objects.requireNonNull(courierCompanyRepository, "courierCompanyRepository");
        this.orderReturnRepository =
                Objects.requireNonNull(orderReturnRepository, "orderReturnRepository");
        this.receivableRepository =
                Objects.requireNonNull(receivableRepository, "receivableRepository");
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.outboxEventPublisher =
                Objects.requireNonNull(outboxEventPublisher, "outboxEventPublisher");
        this.staffNotificationDispatcher =
                Objects.requireNonNull(staffNotificationDispatcher, "staffNotificationDispatcher");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.windowDays = windowDays <= 0 ? 7 : windowDays;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Computes and persists all insights for today (design §Services): gather →
     * project → {@link InsightEngine#compute} → idempotent persist → notify
     * high-severity → audit. Returns the number of insights persisted.
     */
    @Transactional
    public int computeForToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);

        // Current window [today - windowDays + 1 .. today]; previous = the equal-length preceding period.
        LocalDate currentStart = today.minusDays(windowDays - 1L);
        LocalDate previousStart = currentStart.minusDays(windowDays);
        LocalDate previousEnd = currentStart.minusDays(1);
        LocalDateTime currentFrom = currentStart.atStartOfDay();
        LocalDateTime currentTo = today.atTime(java.time.LocalTime.MAX);
        LocalDateTime previousFrom = previousStart.atStartOfDay();
        LocalDateTime previousTo = previousEnd.atTime(java.time.LocalTime.MAX);

        // Gather every family independently so one failure is logged & skipped (Req 1.3).
        SalesWindow sales = safe("sales", () -> gatherSales(currentFrom, currentTo, previousFrom, previousTo), null);
        List<ProductConsumption> products = safe("reorder", () -> gatherProducts(now), List.of());
        List<CourierOutcome> couriers = safe("courier", this::gatherCouriers, List.of());
        List<OpenOrderRisk> openOrders = safe("rto", this::gatherOpenOrders, List.of());
        ReturnStats returns = safe("returns", () -> gatherReturns(currentFrom, currentTo), null);
        CodOutstanding cod = safe("cod", this::gatherCod, null);
        List<LeadSourceConversion> leadSources = safe("lead-source", this::gatherLeadSources, List.of());

        InsightInputs inputs = new InsightInputs(
                sales, products, couriers, openOrders, returns, cod, leadSources);
        List<Insight> insights = engine.compute(inputs, thresholds, today);

        // Capture the notifiable natural keys already present for today BEFORE the
        // delete, so re-running a day never re-notifies an unchanged insight (Req 10.1).
        Set<Insight.NaturalKey> alreadyNotified = new HashSet<>();
        for (InsightEntity existing : insightRepository.findByComputedDateOrderBySeverityAscIdDesc(today)) {
            if (existing.getSeverity() != null && existing.getSeverity().isNotifiable()) {
                alreadyNotified.add(new Insight.NaturalKey(existing.getInsightType(), existing.getScope(),
                        existing.getScopeRefId(), existing.getComputedDate()));
            }
        }

        // Idempotent persist: replace this date's rows (Req 1.2).
        insightRepository.deleteByComputedDate(today);
        List<InsightEntity> toSave = new ArrayList<>(insights.size());
        for (Insight insight : insights) {
            toSave.add(InsightEntity.from(insight));
        }
        List<InsightEntity> saved = insightRepository.saveAll(toSave);

        // Notify newly-notifiable insights (Req 10.1, 10.2); saveAll preserves order.
        for (int i = 0; i < saved.size(); i++) {
            Insight insight = insights.get(i);
            if (!insight.severity().isNotifiable()) {
                continue;
            }
            InsightEntity entity = saved.get(i);
            Insight.NaturalKey key = new Insight.NaturalKey(entity.getInsightType(), entity.getScope(),
                    entity.getScopeRefId(), entity.getComputedDate());
            if (alreadyNotified.contains(key)) {
                continue;
            }
            try {
                notify(entity, insight);
            } catch (RuntimeException e) {
                log.warn("Insight notification failed for {} {}: {}",
                        insight.type(), entity.getId(), e.getMessage());
            }
        }

        auditService.record(null, "SYSTEM", AuditActions.INSIGHTS_COMPUTED, AuditActions.ENTITY_INSIGHT,
                null, "Computed " + saved.size() + " insights for " + today);
        log.info("Insight computation for {} produced {} insight(s)", today, saved.size());
        return saved.size();
    }

    // --- Gather (read-only projections) -------------------------------------

    private SalesWindow gatherSales(LocalDateTime currentFrom, LocalDateTime currentTo,
                                    LocalDateTime previousFrom, LocalDateTime previousTo) {
        BigDecimal current = sumRevenue(orderRepository.findByCreatedAtBetween(currentFrom, currentTo));
        BigDecimal previous = sumRevenue(orderRepository.findByCreatedAtBetween(previousFrom, previousTo));
        return new SalesWindow(current, previous);
    }

    /** Sum of {@code total_amount} over orders, excluding REJECTED/CANCELLED (revenue definition). */
    private static BigDecimal sumRevenue(List<OrderEntity> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderEntity o : orders) {
            OrderStatus s = o.getOrderStatus();
            if (s == OrderStatus.REJECTED || s == OrderStatus.CANCELLED) {
                continue;
            }
            total = total.add(nz(o.getTotalAmount()));
        }
        return total;
    }

    private List<ProductConsumption> gatherProducts(LocalDateTime now) {
        int lookbackDays = thresholds.reorderLookbackDays();
        LocalDateTime lookbackFrom = now.minusDays(lookbackDays);
        List<StockMovement> saleMovements = stockMovementRepository
                .findByMovementTypeAndCreatedAtBetween(StockMovementType.SALE, lookbackFrom, now);

        Map<Long, Long> unitsByProduct = new LinkedHashMap<>();
        for (StockMovement m : saleMovements) {
            if (m.getProductId() == null) {
                continue;
            }
            unitsByProduct.merge(m.getProductId(), (long) Math.abs(m.getDelta()), Long::sum);
        }

        List<ProductConsumption> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : unitsByProduct.entrySet()) {
            Long productId = e.getKey();
            long unitsSold = e.getValue();
            int onHand = stockMovementRepository
                    .findTopByProductIdOrderByCreatedAtDescIdDesc(productId)
                    .map(StockMovement::getBalanceAfter)
                    .orElse(0);
            String name = productRepository.findById(productId)
                    .map(Product::getName)
                    .orElse("Product #" + productId);
            out.add(new ProductConsumption(productId, name, onHand, unitsSold, lookbackDays));
        }
        return out;
    }

    private List<CourierOutcome> gatherCouriers() {
        Map<Long, OrderStatus> statusByOrderId = new HashMap<>();
        for (OrderEntity o : orderRepository.findAll()) {
            statusByOrderId.put(o.getId(), o.getOrderStatus());
        }

        Map<Long, long[]> counts = new LinkedHashMap<>(); // courierCompanyId -> [delivered, rto, failed, other]
        for (CourierRecord r : courierRecordRepository.findAll()) {
            Long courierCompanyId = r.getCourierCompanyId();
            if (courierCompanyId == null) {
                continue;
            }
            OrderStatus s = statusByOrderId.get(r.getOrderId());
            if (s == null || !s.isTerminal()) {
                continue;
            }
            long[] c = counts.computeIfAbsent(courierCompanyId, k -> new long[4]);
            if (DELIVERED_TERMINAL.contains(s)) {
                c[0]++;
            } else if (s == OrderStatus.RTO) {
                c[1]++;
            } else if (FAILED_TERMINAL.contains(s)) {
                c[2]++;
            } else {
                c[3]++;
            }
        }

        List<CourierOutcome> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : counts.entrySet()) {
            long[] c = e.getValue();
            String name = courierCompanyRepository.findById(e.getKey())
                    .map(CourierCompany::getName)
                    .orElse("Courier #" + e.getKey());
            // avgTransitDays is 0.0 this phase (design §Services: acceptable when not derivable).
            out.add(new CourierOutcome(e.getKey(), name, c[0], c[1], c[2], c[3], 0.0));
        }
        return out;
    }

    private List<OpenOrderRisk> gatherOpenOrders() {
        List<OrderEntity> all = orderRepository.findAll();

        // Historical, one-time aggregations: per-state failure rate + prior failed per customer.
        Map<String, long[]> stateStats = new HashMap<>(); // state -> [failedTerminal, allTerminal]
        Map<String, Integer> priorFailedByMobile = new HashMap<>();
        for (OrderEntity o : all) {
            OrderStatus s = o.getOrderStatus();
            if (s != null && s.isTerminal()) {
                String state = o.getState() == null ? "" : o.getState();
                long[] cell = stateStats.computeIfAbsent(state, k -> new long[2]);
                cell[1]++;
                if (FAILED_TERMINAL.contains(s)) {
                    cell[0]++;
                }
            }
            if (FAILED_TERMINAL.contains(s) && o.getCustomerMobile() != null) {
                priorFailedByMobile.merge(o.getCustomerMobile(), 1, Integer::sum);
            }
        }

        List<OpenOrderRisk> out = new ArrayList<>();
        for (OrderEntity o : orderRepository.findByOrderStatusInOrderByCreatedAtDesc(OPEN_STATES)) {
            String state = o.getState() == null ? "" : o.getState();
            long[] cell = stateStats.get(state);
            double stateFailureRate = (cell == null || cell[1] == 0) ? 0.0 : (double) cell[0] / cell[1];
            int priorFailed = o.getCustomerMobile() == null
                    ? 0 : priorFailedByMobile.getOrDefault(o.getCustomerMobile(), 0);
            out.add(new OpenOrderRisk(o.getId(), o.getOrderCode(), o.getCodAmount(),
                    o.getState(), priorFailed, stateFailureRate));
        }
        return out;
    }

    private ReturnStats gatherReturns(LocalDateTime currentFrom, LocalDateTime currentTo) {
        long returns = orderReturnRepository
                .search(null, null, currentFrom, currentTo, PageRequest.of(0, 1))
                .getTotalElements();
        long delivered = 0;
        for (OrderEntity o : orderRepository.findByCreatedAtBetween(currentFrom, currentTo)) {
            if (DELIVERED_TERMINAL.contains(o.getOrderStatus())) {
                delivered++;
            }
        }
        return new ReturnStats(delivered, returns);
    }

    private CodOutstanding gatherCod() {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceivableEntity e : receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType.COD_RECEIVABLE)) {
            total = total.add(nz(e.getAmount()));
        }
        return new CodOutstanding(total);
    }

    private List<LeadSourceConversion> gatherLeadSources() {
        Map<LeadSource, long[]> bySource = new EnumMap<>(LeadSource.class); // source -> [leads, won]
        for (LeadEntity l : leadRepository.findAll()) {
            LeadSource source = l.getLeadSource();
            if (source == null) {
                continue;
            }
            long[] cell = bySource.computeIfAbsent(source, k -> new long[2]);
            cell[0]++;
            if (l.getStatus() == LeadStatus.WON) {
                cell[1]++;
            }
        }
        List<LeadSourceConversion> out = new ArrayList<>();
        for (Map.Entry<LeadSource, long[]> e : bySource.entrySet()) {
            out.add(new LeadSourceConversion(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    // --- Notify -------------------------------------------------------------

    /** Publishes the outbox event + a role-addressed admin notification for one insight. */
    private void notify(InsightEntity entity, Insight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("insightId", entity.getId());
        payload.put("type", insight.type().name());
        payload.put("scope", insight.scope().name());
        payload.put("scopeRefId", entity.getScopeRefId());
        payload.put("severity", insight.severity().name());
        payload.put("computedDate", insight.computedDate().toString());
        OutboxEvent event = outboxEventPublisher.publish(
                OutboxEvent.AGGREGATE_SYSTEM, entity.getId(), OutboxEvent.EVENT_INSIGHT_ALERT, payload);
        staffNotificationDispatcher.dispatchToRole(
                NOTIFICATION_TYPE,
                insight.title(),
                insight.detail(),
                insight.severity().toNotificationSeverity(),
                null,
                null,
                event.getId(),
                Role.ADMIN);
    }

    // --- Helpers ------------------------------------------------------------

    /** Runs one family's gather, logging and returning {@code fallback} on failure (Req 1.3). */
    private <T> T safe(String family, Supplier<T> gather, T fallback) {
        try {
            return gather.get();
        } catch (RuntimeException e) {
            log.warn("Insight family '{}' failed to gather and was skipped: {}", family, e.getMessage());
            return fallback;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
