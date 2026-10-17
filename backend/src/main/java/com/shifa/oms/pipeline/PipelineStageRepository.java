package com.shifa.oms.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for configurable {@link PipelineStage}s (CRM expansion
 * Phase 3, Req 17).
 */
public interface PipelineStageRepository extends JpaRepository<PipelineStage, Long> {

    /** All stages of a pipeline, in display order. */
    List<PipelineStage> findByPipelineIdOrderBySortOrderAscIdAsc(Long pipelineId);

    /** The active stages of a pipeline, in display order. */
    List<PipelineStage> findByPipelineIdAndActiveTrueOrderBySortOrderAscIdAsc(Long pipelineId);

    /** The entry stage of a pipeline (the stage a new lead starts in). */
    Optional<PipelineStage> findFirstByPipelineIdAndIsEntryTrue(Long pipelineId);

    /** The Default-pipeline stage mapped to a given LeadStatus enum name. */
    Optional<PipelineStage> findFirstByPipelineIdAndLegacyStatus(Long pipelineId, String legacyStatus);

    /** The highest current sort order within a pipeline (for appending a new stage). */
    Optional<PipelineStage> findFirstByPipelineIdOrderBySortOrderDesc(Long pipelineId);
}
