package com.shifa.oms.pipeline.dto;

import com.shifa.oms.pipeline.PipelineStage;

/**
 * A configurable pipeline stage returned by the pipeline read/CRUD endpoints
 * (CRM expansion Phase 3, Req 17).
 */
public record PipelineStageResponse(Long id, Long pipelineId, String name, int sortOrder,
                                    boolean isEntry, String terminalKind, String legacyStatus,
                                    boolean active) {

    public static PipelineStageResponse from(PipelineStage stage) {
        return new PipelineStageResponse(stage.getId(), stage.getPipelineId(), stage.getName(),
                stage.getSortOrder(), stage.isEntry(), stage.getTerminalKind(),
                stage.getLegacyStatus(), stage.isActive());
    }
}
