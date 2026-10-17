package com.shifa.oms.lead.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Assign/reassign payload for {@code PUT /api/leads/{id}/assign} (CRM expansion,
 * Req 1). {@code toUserId} is the new assignee (a CRM user or a Team_Lead); when
 * it is a Team_Lead and {@code roundRobin} is true the service MAY distribute
 * across the team (v1 assigns to the Team_Lead directly).
 */
public record AssignLeadRequest(
        @NotNull(message = "toUserId is required")
        Long toUserId,
        Boolean roundRobin
) {
}
