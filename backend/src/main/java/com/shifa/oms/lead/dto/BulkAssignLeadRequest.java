package com.shifa.oms.lead.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Bulk assign/reassign payload for {@code POST /api/leads/bulk-assign} (CRM
 * expansion, Req 1.4): reassign every selected lead to {@code toUserId}, with a
 * per-lead success/failure result.
 */
public record BulkAssignLeadRequest(
        @NotEmpty(message = "ids must contain at least one lead id")
        List<Long> ids,
        @NotNull(message = "toUserId is required")
        Long toUserId
) {
}
