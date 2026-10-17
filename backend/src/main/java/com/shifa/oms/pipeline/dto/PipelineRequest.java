package com.shifa.oms.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create/rename a pipeline (CRM expansion Phase 3, Req 16.1). */
public record PipelineRequest(
        @NotBlank @Size(max = 80) String name,
        Boolean active,
        Integer sortOrder) {
}
