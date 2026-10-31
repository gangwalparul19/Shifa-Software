package com.shifa.oms.performance;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.performance.dto.DirectReportPerformanceResponse;
import com.shifa.oms.performance.dto.TeamOrderRow;
import com.shifa.oms.performance.dto.TeamPerformanceResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Map;

/**
 * Team-lead performance dashboard ({@code /api/team/performance}).
 *
 * <p>TEAM_LEAD sees their own team's rollup; ADMIN sees the whole sales force.
 * The team is resolved server-side from the caller (never a client parameter),
 * so a team lead can only ever see their own team.
 */
@RestController
@RequestMapping("/api/team")
@PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
public class TeamPerformanceController {

    private final TeamPerformanceService teamPerformanceService;
    private final CurrentUserService currentUserService;

    public TeamPerformanceController(TeamPerformanceService teamPerformanceService,
                                     CurrentUserService currentUserService) {
        this.teamPerformanceService = teamPerformanceService;
        this.currentUserService = currentUserService;
    }

    /** The selected reporting window; omitted values default to the current month. */
    @GetMapping("/performance")
    public TeamPerformanceResponse performance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return teamPerformanceService.forCaller(actor, from, to);
    }

    /**
     * One page of the orders behind the figures, newest first, for the selected period.
     * {@code scope=team} is the caller's team; {@code scope=own} is a team lead's own orders.
     * Who is included is decided from the signed-in user, never from the request.
     */
    @GetMapping("/performance/orders")
    public PageResponse<TeamOrderRow> orders(
            @RequestParam(defaultValue = "team") String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        boolean own;
        if ("own".equalsIgnoreCase(scope)) {
            own = true;
        } else if ("team".equalsIgnoreCase(scope)) {
            own = false;
        } else {
            throw new ValidationException("Unknown scope '" + scope + "'. Use 'team' or 'own'.");
        }
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        Pageable pageable = PageRequests.of(page, size, null, Map.of(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(teamPerformanceService.orderPage(actor, own, from, to, pageable));
    }

    /** An authorised drill-down into one direct report's performance. */
    @GetMapping("/performance/{salespersonId}")
    public DirectReportPerformanceResponse detail(@PathVariable Long salespersonId,
                                                  @RequestParam(required = false) Integer days) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return teamPerformanceService.detailForCaller(actor, salespersonId, days);
    }
}
