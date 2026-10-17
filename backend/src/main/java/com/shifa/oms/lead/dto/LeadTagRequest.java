package com.shifa.oms.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/rename/retire a managed lead tag, with an optional pipeline-stage
 * association (CRM expansion Phase 3, Req 18.1).
 */
public record LeadTagRequest(
        @NotBlank @Size(max = 40) String name,
        @Size(max = 16) String color,
        Boolean active,
        Integer sortOrder,
        Long stageId) {
}
