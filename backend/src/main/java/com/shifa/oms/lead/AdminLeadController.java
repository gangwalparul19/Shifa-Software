package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.lead.dto.FollowUpHealthResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin cross-user lead monitors (CRM expansion Phase 2, Task 12; Req 10.3).
 * ADMIN-only (an admin is unscoped, so {@link LeadService#followUpHealth} returns
 * the whole-business view here).
 */
@RestController
@RequestMapping("/api/admin/leads")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLeadController {

    private final LeadService leadService;
    private final CurrentUserService currentUserService;

    public AdminLeadController(LeadService leadService, CurrentUserService currentUserService) {
        this.leadService = leadService;
        this.currentUserService = currentUserService;
    }

    /** Cross-user follow-up health: upcoming / due / missed counts + lists (Req 10.3). */
    @GetMapping("/follow-up-health")
    public FollowUpHealthResponse followUpHealth() {
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        return leadService.followUpHealth(actor);
    }
}
