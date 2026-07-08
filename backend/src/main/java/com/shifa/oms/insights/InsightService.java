package com.shifa.oms.insights;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.insights.domain.InsightScope;
import com.shifa.oms.insights.domain.InsightSeverity;
import com.shifa.oms.insights.domain.InsightType;
import com.shifa.oms.insights.dto.InsightResponse;
import com.shifa.oms.insights.dto.RecomputeResponse;
import com.shifa.oms.order.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The read side of the insights module (statistical-insights-engine, design
 * §Services, §API): lists the latest computed date's insights role-scoped,
 * dismisses one (ADMIN), and drives an on-demand recompute (ADMIN, delegating to
 * {@link InsightComputationService}).
 *
 * <p>Role scoping (Req 9.2) is enforced here, never trusted from the client: an
 * {@code ADMIN} sees every insight for the latest date; a {@code SALESPERSON}
 * sees only insights scoped to <em>them</em> ({@code SALESPERSON} scope with
 * their user id) plus {@code RTO_RISK} insights on orders they created (resolved
 * through the {@link OrderRepository}). No other GLOBAL/admin insight leaks to a
 * salesperson.
 *
 * <p>Dual-constructor {@link Clock} so the dismissal timestamp is deterministic
 * under test; the primary {@code @Autowired} constructor uses the system clock.
 */
@Service
public class InsightService {

    private final InsightRepository repository;
    private final OrderRepository orderRepository;
    private final InsightComputationService computationService;
    private final Clock clock;

    /** Production constructor (Spring): uses the system default-zone clock. */
    @Autowired
    public InsightService(InsightRepository repository,
                          OrderRepository orderRepository,
                          InsightComputationService computationService) {
        this(repository, orderRepository, computationService, Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock (deterministic dismissal timestamps). */
    public InsightService(InsightRepository repository,
                          OrderRepository orderRepository,
                          InsightComputationService computationService,
                          Clock clock) {
        this.repository = repository;
        this.orderRepository = orderRepository;
        this.computationService = computationService;
        this.clock = clock;
    }

    /**
     * Lists the latest computed date's insights, filtered by the optional
     * {@code type}/{@code scope}/{@code severity} params and (unless
     * {@code includeDismissed}) hiding dismissed rows, then role-scoped to the
     * caller (Req 9.1, 9.2, 9.3). Returns an empty list when no insights have ever
     * been computed.
     */
    @Transactional(readOnly = true)
    public List<InsightResponse> list(String type, String scope, String severity,
                                      boolean includeDismissed, AuthPrincipal principal) {
        Optional<LocalDate> latest = repository.findMaxComputedDate();
        if (latest.isEmpty()) {
            return List.of();
        }
        List<InsightResponse> out = new ArrayList<>();
        for (InsightEntity e : repository.findByComputedDateOrderBySeverityAscIdDesc(latest.get())) {
            if (!includeDismissed && e.isDismissed()) {
                continue;
            }
            if (!matchesType(e, type) || !matchesScope(e, scope) || !matchesSeverity(e, severity)) {
                continue;
            }
            if (!visibleTo(e, principal)) {
                continue;
            }
            out.add(InsightResponse.from(e));
        }
        return out;
    }

    /**
     * Dismisses one insight (ADMIN-only, enforced at the controller). Idempotent:
     * a second dismiss leaves the original who/when stamp unchanged (Req 9.3).
     */
    @Transactional
    public InsightResponse dismiss(Long id, AuthPrincipal principal) {
        InsightEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Insight " + id + " does not exist."));
        entity.markDismissed(principal.userId(), LocalDateTime.now(clock));
        return InsightResponse.from(repository.save(entity));
    }

    /**
     * Runs the computation now (ADMIN-only, enforced at the controller) and
     * reports the number persisted + the date computed for (design §API).
     */
    @Transactional
    public RecomputeResponse recompute() {
        int computed = computationService.computeForToday();
        LocalDate date = repository.findMaxComputedDate().orElse(LocalDate.now(clock));
        return new RecomputeResponse(computed, date);
    }

    // --- Scoping + filters --------------------------------------------------

    private boolean visibleTo(InsightEntity e, AuthPrincipal principal) {
        if (principal.role() == Role.ADMIN) {
            return true;
        }
        if (principal.role() == Role.SALESPERSON) {
            if (e.getScope() == InsightScope.SALESPERSON
                    && Objects.equals(e.getScopeRefId(), principal.userId())) {
                return true;
            }
            if (e.getScope() == InsightScope.ORDER && e.getInsightType() == InsightType.RTO_RISK
                    && e.getScopeRefId() != null) {
                return orderRepository.findById(e.getScopeRefId())
                        .map(o -> principal.userId().equals(o.getCreatedBy()))
                        .orElse(false);
            }
            return false;
        }
        return false;
    }

    private static boolean matchesType(InsightEntity e, String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        try {
            return InsightType.from(type) == e.getInsightType();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean matchesScope(InsightEntity e, String scope) {
        if (scope == null || scope.isBlank()) {
            return true;
        }
        try {
            return InsightScope.valueOf(scope.trim().toUpperCase(Locale.ROOT)) == e.getScope();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean matchesSeverity(InsightEntity e, String severity) {
        if (severity == null || severity.isBlank()) {
            return true;
        }
        try {
            return InsightSeverity.valueOf(severity.trim().toUpperCase(Locale.ROOT)) == e.getSeverity();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
