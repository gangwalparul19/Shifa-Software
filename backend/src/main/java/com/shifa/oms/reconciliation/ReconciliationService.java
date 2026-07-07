package com.shifa.oms.reconciliation;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.reconciliation.domain.OrderSettlementView;
import com.shifa.oms.reconciliation.domain.Receivable;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.domain.ReconciliationLedger;
import com.shifa.oms.reconciliation.dto.CourierSummaryResponse;
import com.shifa.oms.reconciliation.dto.ReceivableResponse;
import com.shifa.oms.reconciliation.dto.SegregationResponse;
import com.shifa.oms.reconciliation.dto.UnsettledCodResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reconciliation dashboard's read/settle service (Req 17.4, 18.1&ndash;18.6).
 *
 * <p>It reads the persisted {@link ReceivableEntity} rows created by the
 * settlement side effects wired into courier status changes (task&nbsp;14) and
 * derives the dashboard views by delegating the aggregation and segregation to
 * the pure-domain {@link ReconciliationLedger} (task&nbsp;5). Because the ledger
 * derives outstanding totals from the set of <em>unsettled</em> receivables:
 * <ul>
 *   <li>per-courier COD/claim totals cover only unsettled amounts (Req 18.1, 18.2);</li>
 *   <li>RTO orders never create a receivable, so they are excluded from COD
 *       totals automatically (Req 18.6);</li>
 *   <li>settling a receivable reduces the courier's outstanding by exactly that
 *       amount, and is idempotent for an already-settled row (Req 18.5).</li>
 * </ul>
 *
 * <p>Pending {@code CLAIM_RECEIVABLE} rows (settled=false) surface the claims
 * that need filing for their AWB (Req 17.4); the claim notification itself is
 * emitted at loss time by the courier module (task&nbsp;14), not here.
 */
@Service
public class ReconciliationService {

    /** Fulfilled lifecycle states that belong in the prepaid/COD segregation view (Req 18.4). */
    private static final List<OrderStatus> SEGREGATION_STATUSES = List.of(
            OrderStatus.DELIVERED,
            OrderStatus.COD_COLLECTED,
            OrderStatus.CLOSED,
            OrderStatus.RTO,
            OrderStatus.COURIER_LOST);

    private final ReceivableRepository receivableRepository;
    private final OrderRepository orderRepository;
    private final CourierCompanyRepository courierCompanyRepository;
    private final CourierRecordRepository courierRecordRepository;

    public ReconciliationService(ReceivableRepository receivableRepository,
                                 OrderRepository orderRepository,
                                 CourierCompanyRepository courierCompanyRepository,
                                 CourierRecordRepository courierRecordRepository) {
        this.receivableRepository = receivableRepository;
        this.orderRepository = orderRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.courierRecordRepository = courierRecordRepository;
    }

    /**
     * Lists receivables, optionally filtered by courier company and/or type
     * (Req 18.1&ndash;18.3). Each row is enriched with the order code, courier
     * name, and AWB.
     *
     * @param courierCompanyId restrict to a courier company, or {@code null} for all
     * @param type             restrict to a receivable type, or {@code null} for all
     * @return the matching receivables, newest first
     */
    @Transactional(readOnly = true)
    public List<ReceivableResponse> listReceivables(Long courierCompanyId, ReceivableType type) {
        List<ReceivableEntity> rows = (type == null)
                ? receivableRepository.findAllByOrderByCreatedAtDescIdDesc()
                : receivableRepository.findByTypeOrderByCreatedAtDescIdDesc(type);
        List<ReceivableResponse> result = new ArrayList<>();
        for (ReceivableEntity e : rows) {
            if (courierCompanyId != null && !courierCompanyId.equals(e.getCourierCompanyId())) {
                continue;
            }
            result.add(toResponse(e));
        }
        return result;
    }

    /**
     * Server-side paged / sorted / filtered receivables listing that backs the
     * Wave 2 reconciliation table (ROADMAP 2.2). Extends the existing
     * courier/type filters with an optional {@code settled} flag, a
     * {@code created_at} date range, and a free-text {@code q} that matches the
     * receivable's order by code / customer name / mobile / id / AWB (reusing the
     * existing {@link OrderRepository#search} finder). Each row is enriched with
     * the order code, courier name, and AWB, exactly like {@link #listReceivables}.
     *
     * @param courierCompanyId restrict to a courier company (nullable)
     * @param type             restrict to a receivable type (nullable)
     * @param settled          restrict to settled/unsettled rows (nullable)
     * @param q                free-text order match (nullable/blank → no q filter)
     * @param from             inclusive {@code created_at} lower-bound date (nullable)
     * @param to               inclusive {@code created_at} upper-bound date (nullable)
     * @param pageable         page / size / sort
     * @return a page of receivable rows
     */
    @Transactional(readOnly = true)
    public Page<ReceivableResponse> listReceivables(Long courierCompanyId, ReceivableType type,
                                                    Boolean settled, String q,
                                                    LocalDate from, LocalDate to,
                                                    Pageable pageable) {
        List<Long> orderIds = null;
        if (q != null && !q.isBlank()) {
            // Reuse the role-unscoped order search (admin/accountant see all) to
            // resolve the free-text term to the set of matching order ids.
            orderIds = orderRepository.search(q.trim(), null).stream()
                    .map(OrderEntity::getId)
                    .toList();
        }
        Specification<ReceivableEntity> spec = ReceivableListSpecifications.build(
                courierCompanyId, type, settled, from, to, orderIds);
        return receivableRepository.findAll(spec, pageable).map(this::toResponse);
    }

    /**
     * The per-courier outstanding-receivable summary (Req 18.1, 18.2, 18.6):
     * unsettled COD total, unsettled claim total, and their sum, per courier that
     * has at least one receivable.
     */
    @Transactional(readOnly = true)
    public List<CourierSummaryResponse> perCourierSummary() {
        List<ReceivableEntity> all = receivableRepository.findAllByOrderByCreatedAtDescIdDesc();
        ReconciliationLedger ledger = new ReconciliationLedger();
        // Distinct courier ids in first-seen order for a stable summary ordering.
        Map<Long, Boolean> courierIds = new LinkedHashMap<>();
        for (ReceivableEntity e : all) {
            ledger.record(toDomain(e));
            courierIds.putIfAbsent(courierIdOf(e), Boolean.TRUE);
        }
        List<CourierSummaryResponse> summaries = new ArrayList<>();
        for (Long courierId : courierIds.keySet()) {
            BigDecimal cod = ledger.codReceivableTotal(courierId).toBigDecimal();
            BigDecimal claim = ledger.claimReceivableTotal(courierId).toBigDecimal();
            BigDecimal outstanding = ledger.outstandingForCourier(courierId).toBigDecimal();
            summaries.add(new CourierSummaryResponse(
                    courierId == 0L ? null : courierId, courierName(courierId), cod, claim, outstanding));
        }
        return summaries;
    }

    /**
     * The list of delivered COD orders whose COD receivable has not yet been
     * settled (Req 18.3).
     */
    @Transactional(readOnly = true)
    public List<UnsettledCodResponse> unsettledCod() {
        ReconciliationLedger ledger = new ReconciliationLedger();
        for (ReceivableEntity e : receivableRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            ledger.record(toDomain(e));
        }
        Map<Long, ReceivableEntity> byId = new LinkedHashMap<>();
        for (ReceivableEntity e : receivableRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            byId.put(e.getId(), e);
        }
        List<UnsettledCodResponse> result = new ArrayList<>();
        for (Receivable r : ledger.unsettledCodReceivables()) {
            ReceivableEntity e = byId.get(r.id());
            if (e == null) {
                continue;
            }
            OrderEntity order = orderRepository.findById(e.getOrderId()).orElse(null);
            String awb = awbFor(e.getOrderId());
            result.add(new UnsettledCodResponse(
                    e.getId(),
                    e.getOrderId(),
                    order != null ? order.getOrderCode() : null,
                    order != null ? order.getCustomerName() : null,
                    e.getCourierCompanyId(),
                    courierName(courierIdOf(e)),
                    awb,
                    e.getAmount(),
                    e.getCreatedAt()));
        }
        return result;
    }

    /**
     * Pending claims: unsettled {@code CLAIM_RECEIVABLE} rows that still need
     * filing against their courier/AWB (Req 17.4).
     */
    @Transactional(readOnly = true)
    public List<ReceivableResponse> pendingClaims() {
        List<ReceivableResponse> result = new ArrayList<>();
        for (ReceivableEntity e : receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType.CLAIM_RECEIVABLE)) {
            result.add(toResponse(e));
        }
        return result;
    }

    /**
     * Segregates fulfilled orders into prepaid and COD groups for the
     * reconciliation views (Req 18.4), reusing the pure-domain partition.
     */
    @Transactional(readOnly = true)
    public SegregationResponse segregation() {
        List<OrderEntity> orders =
                orderRepository.findByOrderStatusInOrderByCreatedAtDesc(SEGREGATION_STATUSES);
        Map<Long, OrderEntity> byId = new LinkedHashMap<>();
        List<OrderSettlementView> views = new ArrayList<>();
        for (OrderEntity o : orders) {
            byId.put(o.getId(), o);
            views.add(new OrderSettlementView(
                    o.getId(),
                    0L,
                    o.getPaymentStatus(),
                    Money.of(o.getTotalAmount()),
                    Money.of(o.getCodAmount())));
        }
        ReconciliationLedger.Segregation segregation = ReconciliationLedger.segregate(views);
        List<SegregationResponse.SegregatedOrder> prepaid = mapSegregated(segregation.prepaid(), byId);
        List<SegregationResponse.SegregatedOrder> cod = mapSegregated(segregation.cod(), byId);
        return new SegregationResponse(prepaid, cod, sumTotals(prepaid), sumTotals(cod));
    }

    /**
     * Marks a receivable settled, recording the settlement date and reducing the
     * courier's outstanding by that amount (Req 18.5). Idempotent: settling an
     * already-settled receivable retains its original settlement date and simply
     * returns the current state.
     *
     * @param receivableId the receivable to settle
     * @param date         the settlement date, or {@code null} to use today
     * @return the receivable's state after the (possibly no-op) settlement
     * @throws ResourceNotFoundException if no receivable has the given id
     */
    @Transactional
    public ReceivableResponse settle(Long receivableId, LocalDate date) {
        ReceivableEntity entity = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No receivable with id: " + receivableId));
        LocalDate settlementDate = date != null ? date : LocalDate.now();
        if (entity.settle(settlementDate)) {
            entity = receivableRepository.save(entity);
        }
        return toResponse(entity);
    }

    // --- Mapping helpers ----------------------------------------------------

    private ReceivableResponse toResponse(ReceivableEntity e) {
        OrderEntity order = orderRepository.findById(e.getOrderId()).orElse(null);
        return new ReceivableResponse(
                e.getId(),
                e.getOrderId(),
                order != null ? order.getOrderCode() : null,
                e.getCourierCompanyId(),
                courierName(courierIdOf(e)),
                awbFor(e.getOrderId()),
                e.getType(),
                e.getAmount(),
                e.isSettled(),
                e.getSettledDate(),
                e.getCreatedAt());
    }

    private Receivable toDomain(ReceivableEntity e) {
        Receivable r = new Receivable(
                e.getId(), e.getOrderId(), courierIdOf(e), e.getType(), Money.of(e.getAmount()));
        if (e.isSettled()) {
            r.settle(e.getSettledDate() != null ? e.getSettledDate() : LocalDate.now());
        }
        return r;
    }

    private List<SegregationResponse.SegregatedOrder> mapSegregated(
            List<OrderSettlementView> views, Map<Long, OrderEntity> byId) {
        List<SegregationResponse.SegregatedOrder> result = new ArrayList<>();
        for (OrderSettlementView view : views) {
            OrderEntity o = byId.get(view.orderId());
            if (o == null) {
                continue;
            }
            result.add(new SegregationResponse.SegregatedOrder(
                    o.getId(),
                    o.getOrderCode(),
                    o.getCustomerName(),
                    o.getPaymentStatus(),
                    o.getOrderStatus(),
                    o.getTotalAmount(),
                    o.getCodAmount()));
        }
        return result;
    }

    private static BigDecimal sumTotals(List<SegregationResponse.SegregatedOrder> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (SegregationResponse.SegregatedOrder o : orders) {
            total = total.add(o.totalAmount() != null ? o.totalAmount() : BigDecimal.ZERO);
        }
        return total.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static long courierIdOf(ReceivableEntity e) {
        return e.getCourierCompanyId() == null ? 0L : e.getCourierCompanyId();
    }

    private String courierName(long courierId) {
        if (courierId == 0L) {
            return null;
        }
        return courierCompanyRepository.findById(courierId)
                .map(CourierCompany::getName)
                .orElse(null);
    }

    private String awbFor(Long orderId) {
        Optional<CourierRecord> record = courierRecordRepository.findByOrderId(orderId);
        return record.map(CourierRecord::getAwb).orElse(null);
    }
}
