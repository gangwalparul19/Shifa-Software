package com.shifa.oms.auth.dto;

/**
 * Assigns a salesperson to a team lead. A {@code null} {@code teamLeadId} clears
 * the assignment (unassigns the salesperson).
 */
public record AssignTeamLeadRequest(Long teamLeadId) {
}
