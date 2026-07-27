package com.shifa.oms.performance;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.performance.dto.TeamPerformanceResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** The calling team lead's performance rollup (KPIs + leaderboard + source conversion). */
    @GetMapping("/performance")
    public TeamPerformanceResponse performance() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return teamPerformanceService.forCaller(actor);
    }
}
