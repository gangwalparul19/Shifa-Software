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
        // Only per-salesperson MONTH targets (exclude new week/team rows).
        for (SalesTarget t : targetRepository.findByPeriodTypeAndPeriodMonth(
                TargetPeriodType.MONTH, firstDay)) {
            if (t.getSalespersonId() != null) {
                targetById.put(t.getSalespersonId(), t);
            }
        }

        List<SalesTargetRow> rows = new ArrayList<>();
        // Include TEAM_LEAD users alongside SALESPERSON — a team lead also punches
        // orders, so they get a target/incentive row here too.
        for (User u : userRepository.findByRoleInOrderByCreatedAtDescIdDesc(
                List.of(Role.SALESPERSON, Role.TEAM_LEAD))) {
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
        if (u.getRole() != Role.SALESPERSON && u.getRole() != Role.TEAM_LEAD) {
            throw new ValidationException("Targets can only be set for a salesperson or team lead.");
        }
        YearMonth ym = parseMonth(month);
        LocalDate firstDay = ym.atDay(1);
        SalesTarget target = targetRepository.findBySalespersonIdAndPeriodTypeAndPeriodMonth(
                salespersonId, TargetPeriodType.MONTH, firstDay).orElse(null);
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

    // --- CRM expansion Phase 2 (Req 12): weekly + team + conversion targets -

    /**
     * Pure attainment percentage (CRM expansion Phase 2, Req 12.2):
     * {@code achieved / target * 100} to one decimal, or {@code null} when there
     * is no positive target. Exposed static so the property/unit test asserts the
     * exact formula for both week and month.
     */
    public static Double attainmentPct(BigDecimal achieved, BigDecimal target) {
        if (target == null || target.signum() <= 0) {
            return null;
        }
        BigDecimal a = achieved == null ? BigDecimal.ZERO : achieved;
        return a.multiply(BigDecimal.valueOf(100))
                .divide(target, 1, RoundingMode.HALF_UP).doubleValue();
    }

    /** The Monday of the ISO week containing {@code date} — the WEEK period anchor. */
    public static LocalDate weekAnchor(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(
                java.time.DayOfWeek.MONDAY));
    }

    /**
     * Upserts a per-salesperson target for a period (WEEK or MONTH) with an
     * optional incentive % and conversion goal (Req 12.1). {@code anchor} is any
     * date in the period; it is normalised to the period anchor (first-of-month
     * for MONTH, that week's Monday for WEEK).
     */
    @Transactional
    public SalesTargetRow setSalespersonTarget(Long salespersonId, TargetPeriodType periodType,
                                               LocalDate anchor, BigDecimal targetAmount,
                                               BigDecimal incentivePct, BigDecimal conversionTarget) {
        User u = userRepository.findById(salespersonId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + salespersonId + " does not exist."));
        if (u.getRole() != Role.SALESPERSON && u.getRole() != Role.TEAM_LEAD) {
            throw new ValidationException("Targets can only be set for a salesperson or team lead.");
        }
        TargetPeriodType type = periodType == null ? TargetPeriodType.MONTH : periodType;
        LocalDate periodAnchor = normalizeAnchor(type, anchor);
        SalesTarget target = targetRepository
                .findBySalespersonIdAndPeriodTypeAndPeriodMonth(salespersonId, type, periodAnchor)
                .orElseGet(() -> new SalesTarget(
                        salespersonId, null, type, periodAnchor, targetAmount, incentivePct, conversionTarget));
        target.setTargetAmount(targetAmount);
        target.setIncentivePct(incentivePct);
        target.setConversionTarget(conversionTarget);
        targetRepository.save(target);
        auditService.record(AuditActions.SALES_TARGET_SET, AuditActions.ENTITY_SALES_TARGET,
                String.valueOf(salespersonId),
                "Set " + type + " target " + targetAmount + " for " + u.getUsername()
                        + " (" + periodAnchor + ")");
        return attainmentRow(salespersonId, u.getFullName() == null || u.getFullName().isBlank()
                ? u.getUsername() : u.getFullName(), type, periodAnchor, target);
    }

    /**
     * Upserts a TEAM target (keyed by the team lead) for a period (Req 12.1).
     */
    @Transactional
    public SalesTargetRow setTeamTarget(Long teamLeadId, TargetPeriodType periodType, LocalDate anchor,
                                        BigDecimal targetAmount, BigDecimal conversionTarget) {
        User u = userRepository.findById(teamLeadId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + teamLeadId + " does not exist."));
        if (u.getRole() != Role.TEAM_LEAD) {
            throw new ValidationException("Team targets can only be set for a team lead.");
        }
        TargetPeriodType type = periodType == null ? TargetPeriodType.MONTH : periodType;
        LocalDate periodAnchor = normalizeAnchor(type, anchor);
        SalesTarget target = targetRepository
                .findByTeamLeadIdAndPeriodTypeAndPeriodMonth(teamLeadId, type, periodAnchor)
                .orElseGet(() -> new SalesTarget(
                        null, teamLeadId, type, periodAnchor, targetAmount, null, conversionTarget));
        target.setTargetAmount(targetAmount);
        target.setConversionTarget(conversionTarget);
        targetRepository.save(target);
        auditService.record(AuditActions.SALES_TARGET_SET, AuditActions.ENTITY_SALES_TARGET,
                String.valueOf(teamLeadId),
                "Set " + type + " TEAM target " + targetAmount + " for team of " + u.getUsername()
                        + " (" + periodAnchor + ")");
        return attainmentRow(teamLeadId, "Team: " + (u.getFullName() == null || u.getFullName().isBlank()
                ? u.getUsername() : u.getFullName()), type, periodAnchor, target);
    }

    /**
     * Per-salesperson WEEK target vs achievement for the week containing
     * {@code anchor} (default current week), best attainment first (Req 12.2, 12.3).
     */
    @Transactional(readOnly = true)
    public List<SalesTargetRow> listWeekly(LocalDate anchor) {
        LocalDate weekStart = weekAnchor(anchor == null ? LocalDate.now(clock) : anchor);
        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = weekStart.plusWeeks(1).atStartOfDay();

        Map<Long, BigDecimal> revenueById = new HashMap<>();
        Map<Long, Long> ordersById = new HashMap<>();
        for (OrderRepository.SalespersonRevenueRow r : orderRepository.salespersonRevenueBetween(from, to)) {
            if (r.getSalespersonId() != null) {
                revenueById.put(r.getSalespersonId(), nz(r.getRevenue()));
                ordersById.put(r.getSalespersonId(), r.getOrderCount());
            }
        }
        Map<Long, SalesTarget> targetById = new HashMap<>();
        for (SalesTarget t : targetRepository.findByPeriodTypeAndPeriodMonth(
                TargetPeriodType.WEEK, weekStart)) {
            if (t.getSalespersonId() != null) {
                targetById.put(t.getSalespersonId(), t);
            }
        }
        List<SalesTargetRow> rows = new ArrayList<>();
        for (User u : userRepository.findByRoleInOrderByCreatedAtDescIdDesc(
                List.of(Role.SALESPERSON, Role.TEAM_LEAD))) {
            SalesTarget t = targetById.get(u.getId());
            BigDecimal achieved = revenueById.getOrDefault(u.getId(), BigDecimal.ZERO);
            rows.add(weeklyRow(u, weekStart, t, achieved,
                    ordersById.getOrDefault(u.getId(), 0L)));
        }
        rows.sort(Comparator
                .comparing((SalesTargetRow r) -> r.attainmentPct() == null ? -1.0 : r.attainmentPct())
                .reversed()
                .thenComparing(r -> r.achieved() == null ? BigDecimal.ZERO : r.achieved(),
                        Comparator.reverseOrder()));
        return rows;
    }

    private SalesTargetRow attainmentRow(Long id, String name, TargetPeriodType type,
                                         LocalDate anchor, SalesTarget target) {
        LocalDateTime from;
        LocalDateTime to;
        String label;
        if (type == TargetPeriodType.WEEK) {
            from = anchor.atStartOfDay();
            to = anchor.plusWeeks(1).atStartOfDay();
            label = "W/" + anchor;
        } else {
            YearMonth ym = YearMonth.from(anchor);
            from = ym.atDay(1).atStartOfDay();
            to = ym.plusMonths(1).atDay(1).atStartOfDay();
            label = ym.format(MONTH_FMT);
        }
        BigDecimal achieved = BigDecimal.ZERO;
        long orders = 0;
        for (OrderRepository.SalespersonRevenueRow r : orderRepository.salespersonRevenueBetween(from, to)) {
            if (id.equals(r.getSalespersonId())) {
                achieved = nz(r.getRevenue());
                orders = r.getOrderCount();
            }
        }
        return attainmentRowOf(id, name, label, target, achieved, orders);
    }

    private static SalesTargetRow weeklyRow(User u, LocalDate weekStart, SalesTarget target,
                                            BigDecimal achieved, long orderCount) {
        return attainmentRowOf(u.getId(),
                u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName(),
                "W/" + weekStart, target, achieved, orderCount);
    }

    private static SalesTargetRow attainmentRowOf(Long id, String name, String label,
                                                  SalesTarget target, BigDecimal achieved,
                                                  long orderCount) {
        BigDecimal targetAmount = target == null ? null : target.getTargetAmount();
        BigDecimal incentivePct = target == null ? null : target.getIncentivePct();
        Double attainment = attainmentPct(achieved, targetAmount);
        boolean met = targetAmount != null && targetAmount.signum() > 0
                && achieved.compareTo(targetAmount) >= 0;
        BigDecimal incentiveAmount = BigDecimal.ZERO.setScale(2);
        if (met && incentivePct != null && incentivePct.signum() > 0) {
            incentiveAmount = achieved.multiply(incentivePct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return new SalesTargetRow(id, name, true, label, targetAmount,
                achieved.setScale(2, RoundingMode.HALF_UP), orderCount,
                attainment, incentivePct, incentiveAmount, met);
    }

    private static LocalDate normalizeAnchor(TargetPeriodType type, LocalDate anchor) {
        LocalDate d = anchor == null ? LocalDate.now() : anchor;
        return type == TargetPeriodType.WEEK ? weekAnchor(d) : d.withDayOfMonth(1);
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
