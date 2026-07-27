package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.TeamLeadSummary;
import com.shifa.oms.auth.dto.TeamMemberRow;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin team-management application service: lists team leads, lists salespeople
 * with their current team-lead assignment, and (re)assigns a salesperson to a
 * team lead. Backs the ADMIN-only {@code /api/admin/team} API.
 *
 * <p>Kept separate from the tested {@link AdminUserService} (credential
 * management) so it can own the {@code users.team_lead_id} relationship without
 * touching that service's guardrails. Only a {@link Role#SALESPERSON} may be
 * assigned, and only to a {@link Role#TEAM_LEAD}.
 */
@Service
public class TeamManagementService {

    private final UserRepository userRepository;

    public TeamManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** All team leads with their current member counts (for the assignment picker). */
    @Transactional(readOnly = true)
    public List<TeamLeadSummary> listTeamLeads() {
        return userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD).stream()
                .map(lead -> TeamLeadSummary.from(lead,
                        userRepository.findIdsByTeamLeadId(lead.getId()).size()))
                .toList();
    }

    /** Every salesperson with the team lead (if any) they are assigned to. */
    @Transactional(readOnly = true)
    public List<TeamMemberRow> listSalespeople() {
        Map<Long, String> leadNames = new HashMap<>();
        for (User lead : userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD)) {
            leadNames.put(lead.getId(), lead.getFullName());
        }
        return userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON).stream()
                .map(sp -> new TeamMemberRow(
                        sp.getId(), sp.getFullName(), sp.getUsername(), sp.isActive(),
                        sp.getTeamLeadId(),
                        sp.getTeamLeadId() == null ? null : leadNames.get(sp.getTeamLeadId())))
                .toList();
    }

    /**
     * Assigns a salesperson to a team lead, or clears the assignment when
     * {@code teamLeadId} is null. Validates that the target is a salesperson and
     * the lead is an actual team lead.
     *
     * @return the salesperson's full name (for the audit detail)
     */
    @Transactional
    public String assign(Long salespersonId, Long teamLeadId) {
        User salesperson = userRepository.findById(salespersonId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + salespersonId + " does not exist."));
        if (salesperson.getRole() != Role.SALESPERSON) {
            throw new ValidationException("Only a salesperson can be assigned to a team lead.");
        }
        if (teamLeadId != null) {
            User lead = userRepository.findById(teamLeadId)
                    .orElseThrow(() -> new ResourceNotFoundException("User " + teamLeadId + " does not exist."));
            if (lead.getRole() != Role.TEAM_LEAD) {
                throw new ValidationException("The selected user is not a team lead.");
            }
        }
        salesperson.setTeamLeadId(teamLeadId);
        userRepository.save(salesperson);
        return salesperson.getFullName();
    }
}
