package com.shifa.oms.insights;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.insights.dto.InsightResponse;
import com.shifa.oms.insights.dto.RecomputeResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Statistical-insights REST surface (statistical-insights-engine, design §API).
 * All routes require authentication and are restricted to {@code ADMIN} and
 * {@code SALESPERSON} via the class-level {@code @PreAuthorize}; the two mutating
 * actions ({@code recompute}, {@code dismiss}) are ADMIN-only via method-level
 * guards. Role scoping of the listing (a salesperson sees only their own scoped
 * insights) is enforced inside {@link InsightService}, never trusted from the
 * client.
 *
 * <ul>
 *   <li>{@code GET /api/insights} — list the latest computed date's insights,
 *       role-scoped, with optional {@code type}/{@code scope}/{@code severity}/
 *       {@code includeDismissed} filters (Req 9.1, 9.2).</li>
 *   <li>{@code POST /api/insights/{id}/dismiss} — dismiss one insight (ADMIN,
 *       idempotent, Req 9.3).</li>
 *   <li>{@code POST /api/insights/recompute} — run the computation now (ADMIN,
 *       Req 2.2).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/insights")
@PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")
public class InsightController {

    private final InsightService insightService;
    private final CurrentUserService currentUserService;

    public InsightController(InsightService insightService, CurrentUserService currentUserService) {
        this.insightService = insightService;
        this.currentUserService = currentUserService;
    }

    /** Latest-date insights, role-scoped, with optional filters (Req 9.1, 9.2). */
    @GetMapping
    public List<InsightResponse> list(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "includeDismissed", required = false, defaultValue = "false")
            boolean includeDismissed) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return insightService.list(type, scope, severity, includeDismissed, actor);
    }

    /** Dismiss one insight; ADMIN-only, idempotent (Req 9.3). */
    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    public InsightResponse dismiss(@PathVariable Long id) {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return insightService.dismiss(id, actor);
    }

    /** Run the computation now; ADMIN-only (Req 2.2). */
    @PostMapping("/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public RecomputeResponse recompute() {
        return insightService.recompute();
    }
}
