package com.shifa.oms.pipeline.dto;

import com.shifa.oms.pipeline.Pipeline;

import java.util.List;

/**
 * A configurable pipeline with its stages (CRM expansion Phase 3, Req 16, 17).
 */
public record PipelineResponse(Long id, String name, boolean isDefault, boolean active,
                               int sortOrder, List<PipelineStageResponse> stages) {

    public static PipelineResponse from(Pipeline pipeline, List<PipelineStageResponse> stages) {
        return new PipelineResponse(pipeline.getId(), pipeline.getName(), pipeline.isDefault(),
                pipeline.isActive(), pipeline.getSortOrder(), stages);
    }
}
