package com.shifa.oms.pipeline;

import com.shifa.oms.pipeline.dto.PipelineRequest;
import com.shifa.oms.pipeline.dto.PipelineResponse;
import com.shifa.oms.pipeline.dto.PipelineStageRequest;
import com.shifa.oms.pipeline.dto.PipelineStageResponse;
import com.shifa.oms.pipeline.dto.StageReorderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pipeline configuration REST surface (CRM expansion Phase 3, Req 16, 17).
 *
 * <ul>
 *   <li>Admin CRUD under {@code /api/admin/pipelines} — ADMIN only, widened by
 *       the {@code MANAGE_PIPELINES} CRM permission (Phase 3 Task 20).</li>
 *   <li>A read endpoint {@code GET /api/pipelines} — any lead role — for the
 *       capture/board pickers.</li>
 * </ul>
 */
@RestController
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    // --- Read (any lead role) ----------------------------------------------

    /** The active pipelines + their active stages, for the board / capture pickers. */
    @GetMapping("/api/pipelines")
    @PreAuthorize("hasAnyRole('SALESPERSON','TEAM_LEAD','ADMIN')")
    public List<PipelineResponse> listActive() {
        return pipelineService.listActive();
    }

    // --- Admin CRUD --------------------------------------------------------

    @GetMapping("/api/admin/pipelines")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public List<PipelineResponse> listAll() {
        return pipelineService.listAll();
    }

    @GetMapping("/api/admin/pipelines/{id}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineResponse get(@PathVariable Long id) {
        return pipelineService.get(id);
    }

    @PostMapping("/api/admin/pipelines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineResponse create(@Valid @RequestBody PipelineRequest request) {
        return pipelineService.create(request);
    }

    @PutMapping("/api/admin/pipelines/{id}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineResponse update(@PathVariable Long id,
                                   @Valid @RequestBody PipelineRequest request) {
        return pipelineService.update(id, request);
    }

    @PostMapping("/api/admin/pipelines/{id}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineStageResponse addStage(@PathVariable Long id,
                                          @Valid @RequestBody PipelineStageRequest request) {
        return pipelineService.addStage(id, request);
    }

    @PutMapping("/api/admin/pipelines/{id}/stages/{stageId}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineStageResponse updateStage(@PathVariable Long id, @PathVariable Long stageId,
                                             @Valid @RequestBody PipelineStageRequest request) {
        return pipelineService.updateStage(id, stageId, request);
    }

    @PutMapping("/api/admin/pipelines/{id}/stages/reorder")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public List<PipelineStageResponse> reorderStages(@PathVariable Long id,
                                                     @Valid @RequestBody StageReorderRequest request) {
        return pipelineService.reorderStages(id, request.stageIds());
    }

    @DeleteMapping("/api/admin/pipelines/{id}/stages/{stageId}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_PIPELINES')")
    public PipelineStageResponse retireStage(@PathVariable Long id, @PathVariable Long stageId) {
        return pipelineService.retireStage(id, stageId);
    }
}
