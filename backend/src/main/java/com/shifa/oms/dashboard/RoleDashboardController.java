package com.shifa.oms.dashboard;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.dashboard.dto.RoleDashboardSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-role dashboard summary API (design §6.7, Req 3.1&ndash;3.6).
 *
 * <p>Distinct from the admin-only {@code /api/admin/metrics} surface (which is
 * left untouched), this endpoint is available to all four operational roles and
 * returns a payload shaped for the caller's role, built server-side from their
 * {@link com.shifa.oms.auth.AuthPrincipal}. A {@code SALESPERSON} sees only their
 * own orders (scoped server-side, Req 3.2); the role is never trusted from the
 * client. Method-level {@code @PreAuthorize} enforces access independently of the
 * Angular route guards (Req 2.7).
 *
 * <ul>
 *   <li>{@code GET /api/dashboard/summary} &mdash; the role-shaped dashboard
 *       summary for the authenticated caller (Req 3.1&ndash;3.6).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/dashboard")
public class RoleDashboardController {

    private final RoleDashboardService dashboardService;
    private final CurrentUserService currentUserService;

    public RoleDashboardController(RoleDashboardService dashboardService,
                                   CurrentUserService currentUserService) {
        this.dashboardService = dashboardService;
        this.currentUserService = currentUserService;
    }

    /** The role-shaped dashboard summary for the authenticated caller (Req 3.1&ndash;3.6). */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON','PACKING_USER','ACCOUNTANT')")
    public RoleDashboardSummary summary() {
        return dashboardService.summary(currentUserService.requireCurrentUser());
    }
}
