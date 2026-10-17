package com.shifa.oms.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add/rename a pipeline stage (CRM expansion Phase 3, Req 17.1). {@code terminalKind}
 * is {@code null} for an active stage or one of {@code WON}/{@code LOST}/{@code CLOSED}.
 */
public record PipelineStageRequest(
        @NotBlank @Size(max = 60) String name,
        Boolean isEntry,
        @Size(max = 10) String terminalKind,
        Boolean active,
        Integer sortOrder) {
}
