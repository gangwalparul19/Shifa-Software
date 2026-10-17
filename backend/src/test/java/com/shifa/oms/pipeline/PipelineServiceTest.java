package com.shifa.oms.pipeline;

import com.shifa.oms.lead.LeadStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure legality + default-resolution tests for {@link PipelineService} (CRM
 * expansion Phase 3, Task 18). Covers the custom-pipeline
 * {@link PipelineService#canTransition(List, Long, Long)} rule set and that the
 * Default pipeline resolves its entry stage + a stage for each of the five
 * {@link LeadStatus} values. Only the repository <em>interfaces</em> are mocked
 * (Java 25 gotcha: no mocking of concrete classes).
 */
class PipelineServiceTest {

    // --- canTransition (custom pipeline) -----------------------------------

    @Test
    void forwardMoveBetweenActiveStagesIsLegalBackwardIsNot() {
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        PipelineStage b = stage(2L, 10L, "B", 2, false, null, true);
        List<PipelineStage> stages = List.of(a, b);

        assertTrue(PipelineService.canTransition(stages, 1L, 2L), "forward A→B");
        assertFalse(PipelineService.canTransition(stages, 2L, 1L), "backward B→A");
        assertFalse(PipelineService.canTransition(stages, 1L, 1L), "self-transition");
    }

    @Test
    void closingToATerminalStageIsLegalFromAnyNonTerminalStage() {
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        PipelineStage won = stage(3L, 10L, "Won", 9, false, PipelineStage.TERMINAL_WON, true);
        List<PipelineStage> stages = List.of(a, won);

        assertTrue(PipelineService.canTransition(stages, 1L, 3L), "A→Won (close)");
    }

    @Test
    void reopenFromTerminalToActiveIsLegal() {
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        PipelineStage lost = stage(4L, 10L, "Lost", 9, false, PipelineStage.TERMINAL_LOST, true);
        List<PipelineStage> stages = List.of(a, lost);

        assertTrue(PipelineService.canTransition(stages, 4L, 1L), "re-open Lost→A");
    }

    @Test
    void movingIntoARetiredStageIsNeverLegal() {
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        PipelineStage retired = stage(2L, 10L, "B", 2, false, null, false);
        List<PipelineStage> stages = List.of(a, retired);

        assertFalse(PipelineService.canTransition(stages, 1L, 2L), "A→retired B");
    }

    @Test
    void unknownStagesAreIllegal() {
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        assertFalse(PipelineService.canTransition(List.of(a), 1L, 999L));
        assertFalse(PipelineService.canTransition(List.of(a), null, 1L));
    }

    // --- Default pipeline resolution ---------------------------------------

    @Test
    void defaultPipelineMapsEveryLeadStatusToAStage() {
        Pipeline def = defaultPipeline(1L);
        PipelineRepository pipelineRepo = mock(PipelineRepository.class);
        PipelineStageRepository stageRepo = mock(PipelineStageRepository.class);
        when(pipelineRepo.findFirstByIsDefaultTrue()).thenReturn(Optional.of(def));

        PipelineStage entry = stage(100L, 1L, "New", 1, true, null, true);
        entry.setLegacyStatus("NEW");
        when(stageRepo.findFirstByPipelineIdAndIsEntryTrue(1L)).thenReturn(Optional.of(entry));
        for (LeadStatus status : LeadStatus.values()) {
            PipelineStage s = stage((long) (200 + status.ordinal()), 1L, status.name(),
                    status.ordinal() + 1, status == LeadStatus.NEW, null, true);
            s.setLegacyStatus(status.name());
            when(stageRepo.findFirstByPipelineIdAndLegacyStatus(1L, status.name()))
                    .thenReturn(Optional.of(s));
        }

        PipelineService service = new PipelineService(pipelineRepo, stageRepo);

        assertTrue(service.defaultPipeline().isPresent());
        assertTrue(service.isDefaultPipeline(1L));
        assertFalse(service.isDefaultPipeline(2L));
        assertTrue(service.defaultEntryStage().isPresent(), "entry stage resolved");
        for (LeadStatus status : LeadStatus.values()) {
            assertTrue(service.defaultStageForStatus(status).isPresent(),
                    "stage for " + status + " resolved");
        }
    }

    @Test
    void instanceCanTransitionLoadsPipelineStages() {
        PipelineRepository pipelineRepo = mock(PipelineRepository.class);
        PipelineStageRepository stageRepo = mock(PipelineStageRepository.class);
        PipelineStage a = stage(1L, 10L, "A", 1, true, null, true);
        PipelineStage b = stage(2L, 10L, "B", 2, false, null, true);
        when(stageRepo.findByPipelineIdOrderBySortOrderAscIdAsc(10L))
                .thenReturn(List.of(a, b));
        PipelineService service = new PipelineService(pipelineRepo, stageRepo);

        assertTrue(service.canTransition(10L, 1L, 2L));
        assertFalse(service.canTransition(10L, 2L, 1L));
    }

    // --- fixtures -----------------------------------------------------------

    private static Pipeline defaultPipeline(long id) {
        Pipeline p = new Pipeline("Default", true, 0);
        setId(p, id);
        return p;
    }

    private static PipelineStage stage(long id, long pipelineId, String name, int sortOrder,
                                       boolean entry, String terminalKind, boolean active) {
        PipelineStage s = new PipelineStage(pipelineId, name, sortOrder, entry, terminalKind, null);
        s.setActive(active);
        setId(s, id);
        return s;
    }

    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set id in test fixture", e);
        }
    }
}
