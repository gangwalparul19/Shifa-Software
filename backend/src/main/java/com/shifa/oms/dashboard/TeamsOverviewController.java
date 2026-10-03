package com.shifa.oms.dashboard;

import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Team-wise sales with status" admin dashboard view ({@code GET
 * /api/admin/dashboard/teams}, ADMIN only): every team lead's combined
 * orders/revenue/delivery KPIs, current lead pipeline, and a ranked call-out
 * list of due/overdue leads, so an admin can see where each team is heading
 * and jump straight to the leads that need a call.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class TeamsOverviewController {

    private final TeamsOverviewService service;

    public TeamsOverviewController(TeamsOverviewService service) {
        this.service = service;
    }

    @GetMapping("/teams")
    public TeamsOverviewResponse teams() {
        return service.overview();
    }
}
