package com.shifa.oms.pipeline;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.pipeline.dto.PipelineRequest;
import com.shifa.oms.pipeline.dto.PipelineResponse;
import com.shifa.oms.pipeline.dto.PipelineStageRequest;
import com.shifa.oms.pipeline.dto.PipelineStageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configurable pipeline / stage application service (CRM expansion Phase 3,
 * Req 16, 17; design &sect;3.1).
 *
 * <p><strong>Overlay strategy.</strong> The seeded <em>Default</em> pipeline
 * mirrors the {@link LeadStatus} enum funnel 1:1 and stays governed by the frozen
 * enum transition table in {@code LeadService} — this service never changes that
 * behaviour. Stage-configuration-driven legality ({@link #canTransition}) applies
 * ONLY to leads on a CUSTOM (non-default) pipeline. Since every lead is backfilled
 * onto the Default pipeline and nothing yet starts on a custom pipeline, this is a
 * purely additive capability that cannot regress today's flows.
 *
 * <p>It also exposes the Default pipeline's entry stage + the stage for a given
 * {@link LeadStatus}, used by {@code LeadService} to keep {@code pipeline_id}/
 * {@code stage_id} in sync with the authoritative {@code status} on capture and
 * transition.
 */
@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineStageRepository stageRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineStageRepository stageRepository) {
        this.pipelineRepository = Objects.requireNonNull(pipelineRepository, "pipelineRepository");
        this.stageRepository = Objects.requireNonNull(stageRepository, "stageRepository");
    }

    // --- Pure transition legality (custom pipelines only) -------------------

    /**
     * Pure stage-transition legality for a CUSTOM pipeline, given that pipeline's
     * stages (design &sect;Correctness Properties). The rule set — deliberately
     * permissive so an admin's configured funnel works without an edge table:
     * <ul>
     *   <li>a move to an <em>active</em> stage later in sort order is legal
     *       (forward progress);</li>
     *   <li>a move to a <em>terminal</em> stage is legal from any non-terminal
     *       stage (close as won/lost/closed at any point);</li>
     *   <li>a move from a terminal stage back to an active stage is legal — this
     *       is the configured <em>re-open</em> (Req 5.2), available on custom
     *       pipelines only;</li>
     *   <li>a self-transition or a backward move between two active stages is
     *       illegal (no silent regression within the funnel);</li>
     *   <li>a retired ({@code active=false}) target stage is never legal.</li>
     * </ul>
     * Unknown stage ids (not in {@code stages}) are illegal.
     */
    public static boolean canTransition(List<PipelineStage> stages, Long fromStageId, Long toStageId) {
        if (stages == null || fromStageId == null || toStageId == null
                || fromStageId.equals(toStageId)) {
            return false;
        }
        PipelineStage from = find(stages, fromStageId);
        PipelineStage to = find(stages, toStageId);
        if (from == null || to == null) {
            return false;
        }
        if (!to.isActive()) {
            return false; // never move into a retired stage
        }
        if (to.isTerminal()) {
            return !from.isTerminal(); // close from any non-terminal stage
        }
        if (from.isTerminal()) {
            return true; // re-open: terminal → active (Req 5.2)
        }
        return to.getSortOrder() > from.getSortOrder(); // forward progress only
    }

    private static PipelineStage find(List<PipelineStage> stages, Long id) {
        for (PipelineStage s : stages) {
            if (id.equals(s.getId())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Instance overload: loads the pipeline's stages and evaluates
     * {@link #canTransition(List, Long, Long)}. Used by {@code LeadService} for a
     * lead on a CUSTOM pipeline.
     */
    @Transactional(readOnly = true)
    public boolean canTransition(Long pipelineId, Long fromStageId, Long toStageId) {
        List<PipelineStage> stages =
                stageRepository.findByPipelineIdOrderBySortOrderAscIdAsc(pipelineId);
        return canTransition(stages, fromStageId, toStageId);
    }

    // --- Default-pipeline resolution (used by LeadService) ------------------

    /** The seeded Default pipeline, or empty when the overlay is unseeded. */
    @Transactional(readOnly = true)
    public Optional<Pipeline> defaultPipeline() {
        return pipelineRepository.findFirstByIsDefaultTrue();
    }

    /** The Default pipeline's entry stage (where a new lead starts). */
    @Transactional(readOnly = true)
    public Optional<PipelineStage> defaultEntryStage() {
        return defaultPipeline()
                .flatMap(p -> stageRepository.findFirstByPipelineIdAndIsEntryTrue(p.getId()));
    }

    /** The Default pipeline's stage mapped to the given {@link LeadStatus}. */
    @Transactional(readOnly = true)
    public Optional<PipelineStage> defaultStageForStatus(LeadStatus status) {
        if (status == null) {
            return Optional.empty();
        }
        return defaultPipeline()
                .flatMap(p -> stageRepository.findFirstByPipelineIdAndLegacyStatus(
                        p.getId(), status.name()));
    }

    /** Whether a pipeline id is the Default pipeline (null-safe). */
    @Transactional(readOnly = true)
    public boolean isDefaultPipeline(Long pipelineId) {
        if (pipelineId == null) {
            return false;
        }
        return defaultPipeline().map(p -> p.getId().equals(pipelineId)).orElse(false);
    }

    // --- Reads --------------------------------------------------------------

    /** All pipelines with their stages, in display order (admin CRUD view). */
    @Transactional(readOnly = true)
    public List<PipelineResponse> listAll() {
        return pipelineRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    /** The active pipelines with their active stages (board / capture picker). */
    @Transactional(readOnly = true)
    public List<PipelineResponse> listActive() {
        return pipelineRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(p -> PipelineResponse.from(p,
                        stageRepository.findByPipelineIdAndActiveTrueOrderBySortOrderAscIdAsc(p.getId())
                                .stream().map(PipelineStageResponse::from).toList()))
                .toList();
    }

    /** A single pipeline with all its stages. */
    @Transactional(readOnly = true)
    public PipelineResponse get(Long id) {
        return toResponse(requirePipeline(id));
    }

    private PipelineResponse toResponse(Pipeline p) {
        List<PipelineStageResponse> stages =
                stageRepository.findByPipelineIdOrderBySortOrderAscIdAsc(p.getId()).stream()
                        .map(PipelineStageResponse::from)
                        .toList();
        return PipelineResponse.from(p, stages);
    }

    // --- Pipeline CRUD ------------------------------------------------------

    /** Create a new (custom) pipeline. Names are unique case-insensitively. */
    @Transactional
    public PipelineResponse create(PipelineRequest request) {
        String name = requireName(request.name());
        pipelineRepository.findByNameIgnoreCase(name).ifPresent(p -> {
            throw new ValidationException("A pipeline named \"" + name + "\" already exists.");
        });
        Pipeline pipeline = new Pipeline(name, false,
                request.sortOrder() != null ? request.sortOrder() : 0);
        pipeline.setActive(request.active() == null || request.active());
        return toResponse(pipelineRepository.save(pipeline));
    }

    /** Rename / reorder / activate-deactivate a pipeline. The Default pipeline cannot be deactivated. */
    @Transactional
    public PipelineResponse update(Long id, PipelineRequest request) {
        Pipeline pipeline = requirePipeline(id);
        String name = requireName(request.name());
        pipelineRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ValidationException("A pipeline named \"" + name + "\" already exists.");
                });
        pipeline.setName(name);
        if (request.sortOrder() != null) {
            pipeline.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            if (!request.active() && pipeline.isDefault()) {
                throw new ValidationException("The Default pipeline cannot be deactivated.");
            }
            pipeline.setActive(request.active());
        }
        return toResponse(pipelineRepository.save(pipeline));
    }

    // --- Stage CRUD ---------------------------------------------------------

    /** Append a stage to a pipeline (after its current last stage). */
    @Transactional
    public PipelineStageResponse addStage(Long pipelineId, PipelineStageRequest request) {
        Pipeline pipeline = requirePipeline(pipelineId);
        String name = requireStageName(request.name());
        String terminalKind = normaliseTerminalKind(request.terminalKind());
        int sortOrder = request.sortOrder() != null ? request.sortOrder()
                : stageRepository.findFirstByPipelineIdOrderBySortOrderDesc(pipelineId)
                        .map(s -> s.getSortOrder() + 1).orElse(1);
        PipelineStage stage = new PipelineStage(
                pipeline.getId(), name, sortOrder,
                Boolean.TRUE.equals(request.isEntry()), terminalKind, null);
        stage.setActive(request.active() == null || request.active());
        if (stage.isEntry()) {
            clearEntryFlag(pipelineId, null);
        }
        return PipelineStageResponse.from(stageRepository.save(stage));
    }

    /** Rename / retire / designate entry+terminal on a stage. */
    @Transactional
    public PipelineStageResponse updateStage(Long pipelineId, Long stageId,
                                             PipelineStageRequest request) {
        PipelineStage stage = requireStage(pipelineId, stageId);
        stage.setName(requireStageName(request.name()));
        stage.setTerminalKind(normaliseTerminalKind(request.terminalKind()));
        if (request.sortOrder() != null) {
            stage.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            stage.setActive(request.active());
        }
        if (request.isEntry() != null) {
            if (request.isEntry()) {
                clearEntryFlag(pipelineId, stageId);
            }
            stage.setEntry(request.isEntry());
        }
        return PipelineStageResponse.from(stageRepository.save(stage));
    }

    /** Reorder a pipeline's stages by the given ordered list of ids. */
    @Transactional
    public List<PipelineStageResponse> reorderStages(Long pipelineId, List<Long> orderedIds) {
        requirePipeline(pipelineId);
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new ValidationException("stageIds is required.");
        }
        Map<Long, PipelineStage> byId = new java.util.LinkedHashMap<>();
        for (PipelineStage s : stageRepository.findByPipelineIdOrderBySortOrderAscIdAsc(pipelineId)) {
            byId.put(s.getId(), s);
        }
        int order = 1;
        for (Long id : orderedIds) {
            PipelineStage stage = byId.get(id);
            if (stage != null) {
                stage.setSortOrder(order++);
                stageRepository.save(stage);
            }
        }
        return stageRepository.findByPipelineIdOrderBySortOrderAscIdAsc(pipelineId).stream()
                .map(PipelineStageResponse::from)
                .toList();
    }

    /** Retire a stage (active=false). Retire &ne; delete — history stays valid (Req 17.3). */
    @Transactional
    public PipelineStageResponse retireStage(Long pipelineId, Long stageId) {
        PipelineStage stage = requireStage(pipelineId, stageId);
        stage.setActive(false);
        return PipelineStageResponse.from(stageRepository.save(stage));
    }

    // --- Helpers ------------------------------------------------------------

    private void clearEntryFlag(Long pipelineId, Long exceptStageId) {
        for (PipelineStage s : stageRepository.findByPipelineIdOrderBySortOrderAscIdAsc(pipelineId)) {
            if (s.isEntry() && (exceptStageId == null || !s.getId().equals(exceptStageId))) {
                s.setEntry(false);
                stageRepository.save(s);
            }
        }
    }

    private Pipeline requirePipeline(Long id) {
        return pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline " + id + " does not exist."));
    }

    private PipelineStage requireStage(Long pipelineId, Long stageId) {
        PipelineStage stage = stageRepository.findById(stageId)
                .orElseThrow(() -> new ResourceNotFoundException("Stage " + stageId + " does not exist."));
        if (!Objects.equals(stage.getPipelineId(), pipelineId)) {
            throw new ResourceNotFoundException(
                    "Stage " + stageId + " does not belong to pipeline " + pipelineId + ".");
        }
        return stage;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Pipeline name is required.");
        }
        return name.trim();
    }

    private static String requireStageName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Stage name is required.");
        }
        return name.trim();
    }

    private static String normaliseTerminalKind(String terminalKind) {
        if (terminalKind == null || terminalKind.isBlank()) {
            return null;
        }
        String value = terminalKind.trim().toUpperCase(java.util.Locale.ROOT);
        if (!value.equals(PipelineStage.TERMINAL_WON)
                && !value.equals(PipelineStage.TERMINAL_LOST)
                && !value.equals(PipelineStage.TERMINAL_CLOSED)) {
            throw new ValidationException(
                    "terminalKind must be one of WON, LOST, CLOSED (or empty for an active stage).");
        }
        return value;
    }
}
