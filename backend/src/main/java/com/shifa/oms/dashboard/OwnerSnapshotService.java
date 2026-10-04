package com.shifa.oms.dashboard;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.OwnerSnapshotResponse;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.reconciliation.CodAgingService;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.CodAgingResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the owner's one-screen snapshot (ENHANCEMENT 1.1 / 1.2): today's
 * trading plus the actionable backlog, read-only and derived entirely from
 * existing persisted data via SQL aggregates (no full-table scans).
 *
 * <p>Used by both the admin dashboard "owner overview" strip (1.2) and the daily
 * owner email (1.1), so the two always agree. The approvals / payments / failed /
 * RTO counts come from the shared {@code statusCounts()} GROUP BY; COD figures
 * reuse {@link CodAgingService}; the stuck-shipment count is QuikShipX permanent
 * failures (V75); today's trading + top salesperson use windowed aggregates.
 */
@Service
public class OwnerSnapshotService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final CodAgingService codAgingService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public OwnerSnapshotService(OrderRepository orderRepository,
                                ReceivableRepository receivableRepository,
                                CodAgingService codAgingService,
                                UserRepository userRepository) {
        this(orderRepository, receivableRepository, codAgingService, userRepository,
                Clock.system(BUSINESS_ZONE));
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    OwnerSnapshotService(OrderRepository orderRepository,
                         ReceivableRepository receivableRepository,
                         CodAgingService codAgingService,
                         UserRepository userRepository,
                         Clock clock) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.codAgingService = codAgingService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** Builds the current owner snapshot. */
    @Transactional(readOnly = true)
    public OwnerSnapshotResponse snapshot() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();

        // Today's trading (SQL COUNT + SUM).
        OrderRepository.DayLiveRow live = orderRepository.liveStatsBetween(dayStart, dayEnd);
        long ordersToday = live != null ? live.getOrderCount() : 0;
        BigDecimal revenueToday = scale(live != null ? live.getCollection() : BigDecimal.ZERO);

        // Status-based backlog (one GROUP BY).
        Map<OrderStatus, Long> status = statusCounts();
        long approvals = status.getOrDefault(OrderStatus.PENDING_ADMIN_APPROVAL, 0L);
        long failed = status.getOrDefault(OrderStatus.CUSTOMER_REJECTED, 0L)
                + status.getOrDefault(OrderStatus.DELIVERY_FAILED, 0L);
        long rto = status.getOrDefault(OrderStatus.RTO, 0L);

        long paymentsPending = orderRepository.countByPaymentVerificationStatus(PaymentVerificationStatus.PENDING);
        long stuckShipments = orderRepository.countByQuikShipXFailureReasonIsNotNull();
        long pendingClaims = receivableRepository.countByTypeAndSettledFalse(ReceivableType.CLAIM_RECEIVABLE);

        // COD to collect + over-SLA, reusing the aging service.
        CodAgingResponse aging = codAgingService.aging();
        BigDecimal codToCollect = scale(aging.totalOutstanding());
        long codOverSla = aging.overSlaCount();
        BigDecimal codOverSlaAmount = scale(aging.overSlaAmount());

        // Top salesperson by today's revenue.
        String topName = null;
        BigDecimal topRevenue = BigDecimal.ZERO.setScale(2);
        List<OrderRepository.SalespersonRevenueRow> todayBySalesperson =
                orderRepository.salespersonRevenueBetween(dayStart, dayEnd);
        OrderRepository.SalespersonRevenueRow best = null;
        for (OrderRepository.SalespersonRevenueRow row : todayBySalesperson) {
            if (best == null || nz(row.getRevenue()).compareTo(nz(best.getRevenue())) > 0) {
                best = row;
            }
        }
        if (best != null && best.getSalespersonId() != null) {
            topRevenue = scale(best.getRevenue());
            topName = userRepository.findById(best.getSalespersonId())
                    .map(OwnerSnapshotService::displayName)
                    .orElse(null);
        }

        long attentionTotal = approvals + paymentsPending + failed + pendingClaims + stuckShipments + codOverSla;

        return new OwnerSnapshotResponse(
                today, ordersToday, revenueToday,
                approvals, paymentsPending, failed, rto,
                codToCollect, codOverSla, codOverSlaAmount,
                pendingClaims, stuckShipments,
                topName, topRevenue, attentionTotal);
    }

    private Map<OrderStatus, Long> statusCounts() {
        Map<OrderStatus, Long> map = new EnumMap<>(OrderStatus.class);
        for (OrderRepository.StatusCountRow row : orderRepository.statusCounts()) {
            if (row.getStatus() == null) {
                continue;
            }
            try {
                map.merge(OrderStatus.valueOf(row.getStatus()), row.getCount(), Long::sum);
            } catch (IllegalArgumentException ignored) {
                // Unknown stored status name — skip.
            }
        }
        return map;
    }

    private static String displayName(User u) {
        return (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
