package com.shifa.oms.reconciliation;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.PartialSettlementResponse;
import com.shifa.oms.reconciliation.dto.RemittanceBatchLineResponse;
import com.shifa.oms.reconciliation.dto.RemittanceBatchResponse;
import com.shifa.oms.reconciliation.dto.RemittanceReportSummaryResponse;
import com.shifa.oms.reconciliation.dto.RemittanceRowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read side for the weekly COD settlement report (V99): aggregates the persisted
 * {@link RemittanceBatch} / {@link RemittanceBatchLine} records into KPI totals,
 * a batch listing, and per-order details, all windowed by import date in the
 * business zone (Asia/Kolkata). The authoritative settlements were applied by
 * {@link RemittanceRowProcessor}; this service never mutates anything.
 */
@Service
public class RemittanceReportService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final RemittanceBatchRepository batchRepository;
    private final RemittanceBatchLineRepository lineRepository;
    private final ReceivableRepository receivableRepository;
    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final Clock clock;

    @Autowired
    public RemittanceReportService(RemittanceBatchRepository batchRepository,
                                   RemittanceBatchLineRepository lineRepository,
                                   ReceivableRepository receivableRepository,
                                   OrderRepository orderRepository,
                                   CourierRecordRepository courierRecordRepository,
                                   OrderShipmentRepository orderShipmentRepository) {
        this(batchRepository, lineRepository, receivableRepository, orderRepository,
                courierRecordRepository, orderShipmentRepository, Clock.system(BUSINESS_ZONE));
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    RemittanceReportService(RemittanceBatchRepository batchRepository,
                            RemittanceBatchLineRepository lineRepository,
                            ReceivableRepository receivableRepository,
                            OrderRepository orderRepository,
                            CourierRecordRepository courierRecordRepository,
                            OrderShipmentRepository orderShipmentRepository,
                            Clock clock) {
        this.batchRepository = batchRepository;
        this.lineRepository = lineRepository;
        this.receivableRepository = receivableRepository;
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.orderShipmentRepository = orderShipmentRepository;
        this.clock = clock;
    }

    /**
     * Committed remittance batches whose import date falls in the window, newest
     * first. When both bounds are null, returns all batches.
     */
    @Transactional(readOnly = true)
    public List<RemittanceBatchResponse> batches(LocalDate from, LocalDate to) {
        List<RemittanceBatch> rows;
        if (from == null && to == null) {
            rows = batchRepository.findAllByOrderByCreatedAtDescIdDesc();
        } else {
            LocalDate[] window = resolveWindow(from, to);
            rows = batchRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                    startOfDay(window[0]), endOfDayExclusive(window[1]));
        }
        return rows.stream().map(RemittanceBatchResponse::from).toList();
    }

    /** All per-order lines of one committed batch, in file order — the details report. */
    @Transactional(readOnly = true)
    public List<RemittanceBatchLineResponse> lines(Long batchId) {
        if (!batchRepository.existsById(batchId)) {
            throw new ValidationException("Remittance batch " + batchId + " does not exist.");
        }
        return lineRepository.findByBatchIdOrderBySourceRowNumberAsc(batchId).stream()
                .map(RemittanceBatchLineResponse::from).toList();
    }

    /**
     * KPI summary over the window (defaults to the CURRENT week — Monday..Sunday —
     * when both bounds are null), plus the current courier COD still outstanding
     * across all time.
     */
    @Transactional(readOnly = true)
    public RemittanceReportSummaryResponse summary(LocalDate from, LocalDate to) {
        LocalDate[] window = (from == null && to == null) ? currentWeek() : resolveWindow(from, to);
        List<RemittanceBatch> batches = batchRepository.findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(
                startOfDay(window[0]), endOfDayExclusive(window[1]));

        long settled = 0;
        long partial = 0;
        long review = 0;
        BigDecimal totalRemitted = BigDecimal.ZERO;
        BigDecimal totalSettled = BigDecimal.ZERO;
        BigDecimal totalShortfall = BigDecimal.ZERO;
        for (RemittanceBatch b : batches) {
            settled += b.getSettledCount();
            partial += b.getPartialCount();
            review += b.getMismatchCount() + b.getNotFoundCount() + b.getNoReceivableCount() + b.getErrorCount();
            totalRemitted = totalRemitted.add(nz(b.getTotalRemitted()));
            totalSettled = totalSettled.add(nz(b.getTotalSettled()));
            totalShortfall = totalShortfall.add(nz(b.getTotalShortfall()));
        }

        BigDecimal codOutstanding = nz(receivableRepository
                .sumAmountByTypeAndSettled(ReceivableType.COD_RECEIVABLE, false));

        return new RemittanceReportSummaryResponse(
                window[0], window[1], batches.size(), settled, partial, review,
                scale(totalRemitted), scale(totalSettled), scale(totalShortfall), scale(codOutstanding));
    }

    /**
     * All partially-settled COD orders with a shortfall still outstanding — the
     * "chase next cycle" list. These are flagged settled (so they are NOT on the
     * unsettled-COD list), making this the only place they surface. Across all
     * time, newest settlement first; the order code / customer / AWB are batch-
     * resolved to avoid an N+1.
     */
    @Transactional(readOnly = true)
    public List<PartialSettlementResponse> partiallySettled() {
        List<ReceivableEntity> rows = receivableRepository.findPartiallySettled(ReceivableType.COD_RECEIVABLE);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> orderIds = new HashSet<>();
        for (ReceivableEntity r : rows) {
            if (r.getOrderId() != null) {
                orderIds.add(r.getOrderId());
            }
        }
        Map<Long, OrderEntity> orders = new HashMap<>();
        for (OrderEntity o : orderRepository.findAllById(orderIds)) {
            orders.put(o.getId(), o);
        }
        // AWB: prefer the courier record, fall back to the QuikShipX shipment (both batch-loaded).
        Map<Long, String> awbByOrderId = new HashMap<>();
        for (OrderShipment s : orderShipmentRepository.findByOrderIdIn(orderIds)) {
            if (s.getAwb() != null) {
                awbByOrderId.put(s.getOrderId(), s.getAwb());
            }
        }
        for (CourierRecord cr : courierRecordRepository.findByOrderIdIn(orderIds)) {
            if (cr.getAwb() != null) {
                awbByOrderId.put(cr.getOrderId(), cr.getAwb());
            }
        }

        List<PartialSettlementResponse> result = new ArrayList<>();
        for (ReceivableEntity r : rows) {
            OrderEntity o = orders.get(r.getOrderId());
            BigDecimal settled = r.getSettledAmount() != null ? r.getSettledAmount() : BigDecimal.ZERO;
            result.add(new PartialSettlementResponse(
                    r.getId(),
                    r.getOrderId(),
                    o != null ? o.getOrderCode() : null,
                    o != null ? o.getCustomerName() : null,
                    awbByOrderId.get(r.getOrderId()),
                    scale(r.getAmount()),
                    scale(settled),
                    scale(r.getShortfall()),
                    r.getSettledDate()));
        }
        return result;
    }

    // --- Window helpers -------------------------------------------------------

    private LocalDate[] currentWeek() {
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        return new LocalDate[] {monday, sunday};
    }

    /** Resolves a possibly-partial window: a missing bound defaults to the other (or today). */
    private LocalDate[] resolveWindow(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate lo = from != null ? from : (to != null ? to : today);
        LocalDate hi = to != null ? to : (from != null ? from : today);
        if (hi.isBefore(lo)) {
            LocalDate tmp = lo;
            lo = hi;
            hi = tmp;
        }
        return new LocalDate[] {lo, hi};
    }

    private LocalDateTime startOfDay(LocalDate d) {
        return d.atStartOfDay();
    }

    private LocalDateTime endOfDayExclusive(LocalDate d) {
        return d.plusDays(1).atStartOfDay();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
