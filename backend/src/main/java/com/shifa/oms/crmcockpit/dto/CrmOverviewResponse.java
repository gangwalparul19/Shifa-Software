package com.shifa.oms.crmcockpit.dto;

import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.lead.dto.FollowUpHealthResponse;
import com.shifa.oms.performance.dto.SalesTargetRow;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;

import java.util.List;
import java.util.Map;

/**
 * The unified CRM cockpit overview (CRM expansion Phase 2, Task 15; Req 14). A
 * thin composition of existing read models — lead pipeline, follow-up health,
 * monthly targets, and the performance leaderboard — so one call powers the
 * admin cockpit without duplicating any computation (Req 14.3).
 *
 * @param pipeline       active-lead counts per stage (from LeadService)
 * @param followUpHealth upcoming/due/missed follow-up health (from LeadService)
 * @param targets        monthly target-vs-achievement rows (from SalesTargetService)
 * @param leaderboard    salesperson performance headline (from SalespersonPerformanceService)
 */
public record CrmOverviewResponse(
        Map<LeadStatus, Long> pipeline,
        FollowUpHealthResponse followUpHealth,
        List<SalesTargetRow> targets,
        List<SalespersonPerformanceSummary> leaderboard) {
}
