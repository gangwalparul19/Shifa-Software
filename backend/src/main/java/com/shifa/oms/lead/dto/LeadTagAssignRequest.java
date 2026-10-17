package com.shifa.oms.lead.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Add-tag payload for {@code POST /api/leads/{id}/tags} (CRM expansion, Req 4).
 */
public record LeadTagAssignRequest(
        @NotNull(message = "tagId is required")
        Long tagId
) {
}
