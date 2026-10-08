package com.shifa.oms.auth.dto;

/**
 * A salesperson row for the admin team-management screen: identity plus the team
 * lead they are currently assigned to (null when unassigned).
 */
public record TeamMemberRow(
        Long id,
        String fullName,
        String username,
        boolean active,
        Long teamLeadId,
        String teamLeadName) {
}
