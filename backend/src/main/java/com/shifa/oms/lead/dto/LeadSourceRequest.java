package com.shifa.oms.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create/rename/activate a managed lead source (CRM expansion Phase 3, Req 18.2). */
public record LeadSourceRequest(
        @NotBlank @Size(max = 40) String name,
        Boolean active,
        Integer sortOrder) {
}
