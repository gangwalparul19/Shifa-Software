package com.shifa.oms.performance;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.performance.dto.SalesTargetRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales targets & incentive tracking (FEATURE-ROADMAP §6.1). An admin sets a
 * monthly revenue target (and optional incentive %) per salesperson; this service
 * compares it to achieved revenue for the month to produce attainment + a
 * computed incentive. Read + upsert; ADMIN-guarded at the controller.
 */
@Service
public class SalesTargetService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SalesTargetRepository targetRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public SalesTargetService(SalesTargetRepository targetRepository,
                              OrderRepository orderRepository,
                              UserRepository userRepository,
                              AuditService auditService) {
        this(targetRepository, orderRepository, userRepository, auditService, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    SalesTargetService(SalesTargetRepository targetRepository,
                       OrderRepository orderRepository,
                       UserRepository userRepository,
                       AuditService auditService,
                       Clock clock) {
        this.targetRepository = targetRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Per-salesperson target vs achievement for a month, best attainment first.
     *
     * @param month {@code yyyy-MM}, or null for the current month
     */
    @Transactional(readOnly = true)
    public List<SalesTargetRow> list(String month) {
        YearMonth ym = parseMonth(month);
        LocalDate firstDay = ym.atDay(1);
        LocalDateTime from = firstDay.atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        Map<Long, BigDecimal> revenueById = new HashMap<>();
        Map<Long, Long> ordersById = new HashMap<>();
        for (OrderRepository.SalespersonRevenueRow r : orderRepository.salespersonRevenueBetween(from, to)) {
            if (r.getSalespersonId() != null) {
                revenueById.put(r.getSalespersonId(), nz(r.getRevenue()));
                ordersById.put(r.getSalespersonId(), r.getOrderCount());
            }
        }
        Map<Long, SalesTarget> targetById = new HashMap<>();
        for (SalesTarget t : targetRepository.findByPeriodMonth(firstDay)) {
            targetById.put(t.getSalespersonId(), t);
        }

        List<SalesTargetRow> rows = new ArrayList<>();
        for (User u : userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)) {
            rows.add(row(u, ym, targetById.get(u.getId()),
                    revenueById.getOrDefault(u.getId(), BigDecimal.ZERO),
                    ordersById.getOrDefault(u.getId(), 0L)));
        }
        rows.sort(Comparator
                .comparing((SalesTargetRow r) -> r.attainmentPct() == null ? -1.0 : r.attainmentPct())
                .reversed()
                .thenComparing(r -> r.achieved() == null ? BigDecimal.ZERO : r.achieved(),
                        Comparator.reverseOrder()));
        return rows;
    }

    private static SalesTargetRow row(User u, YearMonth ym, SalesTarget target,
                                      BigDecimal achieved, long orderCount) {
        BigDecimal targetAmount = target == null ? null : target.getTargetAmount();
        BigDecimal incentivePct = target == null ? null : target.getIncentivePct();
        Double attainment = null;
        boolean met = false;
        BigDecimal incentiveAmount = BigDecimal.ZERO.setScale(2);
        if (targetAmount != null && targetAmount.signum() > 0) {
            attainment = achieved.multiply(BigDecimal.valueOf(100))
                    .divide(targetAmount, 1, RoundingMode.HALF_UP).doubleValue();
            met = achieved.compareTo(targetAmount) >= 0;
            if (met && incentivePct != null && incentivePct.signum() > 0) {
                incentiveAmount = achieved.multiply(incentivePct)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
        }
        return new SalesTargetRow(
                u.getId(), u.getFullName() == null || u.getFullName().isBlank()
                        ? u.getUsername() : u.getFullName(),
                u.isActive(), ym.format(MONTH_FMT), targetAmount,
                achieved.setScale(2, RoundingMode.HALF_UP), orderCount,
                attainment, incentivePct, incentiveAmount, met);
    }

    /** Upserts a salesperson's monthly target + optional incentive rate. */
    @Transactional
    public SalesTargetRow setTarget(Long salespersonId, String month, BigDecimal targetAmount,
                                    BigDecimal incentivePct) {
        User u = userRepository.findById(salespersonId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + salespersonId + " does not exist."));
        if (u.getRole() != Role.SALESPERSON) {
            throw new ValidationException("Targets can only be set for a salesperson.");
        }
        YearMonth ym = parseMonth(month);
        LocalDate firstDay = ym.atDay(1);
        SalesTarget target = targetRepository.findBySalespersonIdAndPeriodMonth(salespersonId, firstDay)
                .orElse(null);
        if (target == null) {
            target = new SalesTarget(salespersonId, firstDay, targetAmount, incentivePct);
        } else {
            target.setTargetAmount(targetAmount);
            target.setIncentivePct(incentivePct);
        }
        targetRepository.save(target);
        auditService.record(AuditActions.SALES_TARGET_SET, AuditActions.ENTITY_SALES_TARGET,
                String.valueOf(salespersonId),
                "Set " + ym.format(MONTH_FMT) + " target " + targetAmount + " for " + u.getUsername());
        // Return the freshly-computed attainment row for the month.
        final SalesTarget savedTarget = target;
        return list(month).stream()
                .filter(r -> r.salespersonId().equals(salespersonId))
                .findFirst()
                .orElseGet(() -> row(u, ym, savedTarget, BigDecimal.ZERO, 0));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(clock);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (RuntimeException e) {
            throw new ValidationException("month must be in yyyy-MM format.");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
