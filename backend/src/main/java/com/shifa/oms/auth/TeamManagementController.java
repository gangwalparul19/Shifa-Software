package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.dto.AssignTeamLeadRequest;
import com.shifa.oms.auth.dto.TeamLeadSummary;
import com.shifa.oms.auth.dto.TeamMemberRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin team-management API ({@code /api/admin/team}, ADMIN-only).
 *
 * <p>Lets an admin see the team leads and every salesperson's current
 * assignment, and (re)assign a salesperson to a team lead — which drives the
 * team-scoped visibility a {@link Role#TEAM_LEAD} gets over their team's orders.
 * Credential/role management stays on {@link AdminUserController}; this owns only
 * the {@code users.team_lead_id} relationship.
 */
@RestController
@RequestMapping("/api/admin/team")
@PreAuthorize("hasRole('ADMIN')")
public class TeamManagementController {

    private final TeamManagementService teamService;
    private final AuditService auditService;

    public TeamManagementController(TeamManagementService teamService, AuditService auditService) {
        this.teamService = teamService;
        this.auditService = auditService;
    }

    /** All team leads with their member counts (for the assignment picker). */
    @GetMapping("/leads")
    public List<TeamLeadSummary> leads() {
        return teamService.listTeamLeads();
    }

    /** Every salesperson with their current team-lead assignment. */
    @GetMapping("/salespeople")
    public List<TeamMemberRow> salespeople() {
        return teamService.listSalespeople();
    }

    /** Assigns a salesperson to a team lead (null teamLeadId clears the assignment). */
    @PutMapping("/salespeople/{id}")
    public ResponseEntity<Void> assign(@PathVariable Long id,
                                       @RequestBody AssignTeamLeadRequest request) {
        Long teamLeadId = request == null ? null : request.teamLeadId();
        String name = teamService.assign(id, teamLeadId);
        auditService.record(AuditActions.STAFF_PROFILE_UPDATED, AuditActions.ENTITY_USER,
                String.valueOf(id),
                teamLeadId == null
                        ? "Unassigned " + name + " from their team lead"
                        : "Assigned " + name + " to team lead #" + teamLeadId);
        return ResponseEntity.noContent().build();
    }
}
