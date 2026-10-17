package com.shifa.oms.crmcockpit;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.crmcockpit.dto.CrmOverviewResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unified CRM cockpit endpoint (CRM expansion Phase 2, Task 15; Req 14).
 * ADMIN-only; one call composes the pipeline, follow-up health, targets, and
 * performance leaderboard for the admin dashboard.
 */
@RestController
@RequestMapping("/api/admin/crm")
@PreAuthorize("hasRole('ADMIN')")
public class CrmOverviewController {

    private final CrmOverviewService overviewService;
    private final CurrentUserService currentUserService;

    public CrmOverviewController(CrmOverviewService overviewService,
                                 CurrentUserService currentUserService) {
        this.overviewService = overviewService;
        this.currentUserService = currentUserService;
    }

    /** The CRM cockpit overview (Req 14.1, 14.2). */
    @GetMapping("/overview")
    public CrmOverviewResponse overview() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return overviewService.overview(actor);
    }
}
