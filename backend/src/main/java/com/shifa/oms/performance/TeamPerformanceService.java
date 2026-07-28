package com.shifa.oms.performance;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadReportRecord;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.performance.dto.TeamPerformanceResponse;
import com.shifa.oms.performance.dto.TeamSourceConversion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team-lead performance analytics: rolls up the orders + leads of the
 * salespeople assigned to a team lead into headline KPIs, a per-salesperson
 * leaderboard, and lead-source conversion (which source converts best).
 *
 * <p>The team is resolved server-side from the caller's
 * {@link SalespersonScopeResolver#creatorScope(AuthPrincipal)}: a TEAM_LEAD sees
 * exactly their assigned salespeople; an ADMIN (unscoped) sees the whole sales
 * force. Read-only aggregation reusing {@link SalespersonPerformanceService} for
 * the leaderboard — no new tables.
 */
@Service
public class TeamPerformanceService {

    private final SalespersonPerformanceService performanceService;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final SalespersonScopeResolver scopeResolver;

    public TeamPerformanceService(SalespersonPerformanceService performanceService,
                                  LeadRepository leadRepository,
                                  UserRepository userRepository,
                                  SalespersonScopeResolver scopeResolver) {
        this.performanceService = performanceService;
        this.leadRepository = leadRepository;
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
    }

    /** The team-performance rollup for the calling team lead (or whole force for an admin). */
    @Transactional(readOnly = true)
    public TeamPerformanceResponse forCaller(AuthPrincipal actor) {
        List<Long> memberIds = resolveMemberIds(actor);

        List<SalespersonPerformanceSummary> leaderboard = performanceService.leaderboardFor(memberIds);

        // Overall KPIs = sum across the team's leaderboard rows.
        long ordersTotal = 0;
        long ordersThisMonth = 0;
        long delivered = 0;
        long failed = 0;
        BigDecimal revenueTotal = BigDecimal.ZERO;
        BigDecimal revenueThisMonth = BigDecimal.ZERO;
        BigDecimal codOutstanding = BigDecimal.ZERO;
        for (SalespersonPerformanceSummary s : leaderboard) {
            ordersTotal += s.ordersTotal();
            ordersThisMonth += s.ordersThisMonth();
            delivered += s.deliveredCount();
            failed += s.failedCount();
            revenueTotal = revenueTotal.add(nz(s.revenueTotal()));
            revenueThisMonth = revenueThisMonth.add(nz(s.revenueThisMonth()));
            codOutstanding = codOutstanding.add(nz(s.codOutstanding()));
        }
        // Leaderboard is sorted best-this-month first, so the head is the top performer.
        String topPerformer = leaderboard.isEmpty() ? null : leaderboard.get(0).fullName();

        // Lead-source conversion across the team.
        List<LeadEntity> teamLeads = memberIds.isEmpty()
                ? List.of()
                : leadRepository.findAllScopedIn(memberIds);
        Map<String, long[]> bySource = new LinkedHashMap<>();
        long leadsTotal = 0;
        long leadsWon = 0;
        for (LeadEntity lead : teamLeads) {
            LeadReportRecord r = LeadReportRecord.from(lead);
            String source = r.source() == null ? "OTHER" : r.source().name();
            long[] cell = bySource.computeIfAbsent(source, k -> new long[2]);
            cell[0]++;
            leadsTotal++;
            if (r.status() == LeadStatus.WON) {
                cell[1]++;
                leadsWon++;
            }
        }
        List<TeamSourceConversion> leadSources = new ArrayList<>();
        bySource.forEach((source, c) ->
                leadSources.add(new TeamSourceConversion(source, c[0], c[1], pct(c[1], c[0]))));
        // Best-converting source first; ties broken by lead volume.
        leadSources.sort(Comparator
                .comparingDouble(TeamSourceConversion::conversionRate).reversed()
                .thenComparing(Comparator.comparingLong(TeamSourceConversion::leads).reversed()));
        String topSource = leadSources.stream()
                .filter(s -> s.leads() > 0)
                .findFirst()
                .map(TeamSourceConversion::source)
                .orElse(null);

        return new TeamPerformanceResponse(
                memberIds.size(),
                ordersTotal, ordersThisMonth,
                revenueTotal, revenueThisMonth,
                delivered, failed, pct(delivered, delivered + failed),
                codOutstanding,
                leadsTotal, leadsWon, pct(leadsWon, leadsTotal),
                topPerformer, topSource,
                leaderboard, leadSources);
    }

    /**
     * The salesperson ids in scope: a TEAM_LEAD's assigned team (possibly empty),
     * or — for an unscoped ADMIN — every salesperson.
     */
    private List<Long> resolveMemberIds(AuthPrincipal actor) {
        // Membership only (excludes the lead's own id) — a team lead is the
        // MANAGER of their salespeople, not a member of their own team; their own
        // punched orders are handled by order-visibility (creatorScope), not here.
        return scopeResolver.teamMemberScope(actor)
                .orElseGet(() -> userRepository
                        .findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)
                        .stream().map(User::getId).toList());
    }

    /** {@code numerator / denominator} as a percentage (0–100, 1 dp); 0 when denominator is 0. */
    private static double pct(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
