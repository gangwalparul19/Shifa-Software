package com.shifa.oms.crmcockpit;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.crmcockpit.dto.CrmOverviewResponse;
import com.shifa.oms.lead.LeadService;
import com.shifa.oms.performance.SalesTargetService;
import com.shifa.oms.performance.SalespersonPerformanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CRM cockpit aggregator (CRM expansion Phase 2, Task 15; Req 14). It owns
 * NO computation of its own — it fans out to the existing {@link LeadService},
 * {@link SalesTargetService}, and {@link SalespersonPerformanceService} and
 * assembles their read models into one response (Req 14.3), so the admin
 * dashboard can load the whole CRM picture in a single call without risking
 * figures that drift from their detailed views.
 */
@Service
public class CrmOverviewService {

    private final LeadService leadService;
    private final SalesTargetService salesTargetService;
    private final SalespersonPerformanceService performanceService;

    public CrmOverviewService(LeadService leadService,
                              SalesTargetService salesTargetService,
                              SalespersonPerformanceService performanceService) {
        this.leadService = leadService;
        this.salesTargetService = salesTargetService;
        this.performanceService = performanceService;
    }

    /** Composes the admin CRM overview (Req 14.1). The caller is an ADMIN (unscoped). */
    @Transactional(readOnly = true)
    public CrmOverviewResponse overview(AuthPrincipal actor) {
        return new CrmOverviewResponse(
                leadService.pipelineCounts(actor),
                leadService.followUpHealth(actor),
                salesTargetService.list(null),
                performanceService.leaderboard());
    }
}
