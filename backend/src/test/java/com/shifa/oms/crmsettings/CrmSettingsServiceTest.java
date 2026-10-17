package com.shifa.oms.crmsettings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.crmsettings.dto.CrmSettingsRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Round-trip + validation tests for {@link CrmSettingsService} (CRM expansion
 * Phase 3, Task 19). Create-on-missing defaults, update persists, invalid
 * assignment rule is rejected. The repository interface is mocked over an
 * in-memory store.
 */
class CrmSettingsServiceTest {

    @Test
    void getSettingsCreatesDefaultsWhenMissing() {
        CrmSettingsService service = new CrmSettingsService(repo(new ArrayList<>()));
        CrmSettings settings = service.getSettings();
        assertEquals(24, settings.getMissedGraceHours());
        assertEquals(CrmSettings.RULE_DIRECT, settings.getDefaultAssignmentRule());
        assertEquals(LocalTime.of(9, 0), settings.getFollowUpDefaultTime());
    }

    @Test
    void updateRoundTripsConfiguredValues() {
        CrmSettingsService service = new CrmSettingsService(repo(new ArrayList<>()));
        CrmSettings saved = service.update(new CrmSettingsRequest(
                LocalTime.of(10, 30), 48, 7L, "round_robin"));

        assertEquals(LocalTime.of(10, 30), saved.getFollowUpDefaultTime());
        assertEquals(48, saved.getMissedGraceHours());
        assertEquals(7L, saved.getDefaultPipelineId());
        assertEquals(CrmSettings.RULE_ROUND_ROBIN, saved.getDefaultAssignmentRule());

        assertEquals(48, service.missedGraceHours().orElseThrow());
        assertEquals(LocalTime.of(10, 30), service.followUpDefaultTime().orElseThrow());
        assertTrue(service.defaultAssignmentRule().isPresent());
    }

    @Test
    void updateRejectsUnknownAssignmentRule() {
        CrmSettingsService service = new CrmSettingsService(repo(new ArrayList<>()));
        assertThrows(ValidationException.class, () -> service.update(
                new CrmSettingsRequest(null, 24, null, "LOTTERY")));
    }

    private static CrmSettingsRepository repo(List<CrmSettings> store) {
        CrmSettingsRepository repo = mock(CrmSettingsRepository.class);
        lenient().when(repo.findById(CrmSettings.SINGLETON_ID)).thenAnswer(inv ->
                store.stream().findFirst());
        lenient().when(repo.save(any(CrmSettings.class))).thenAnswer(inv -> {
            CrmSettings s = inv.getArgument(0);
            if (store.isEmpty()) {
                store.add(s);
            } else {
                store.set(0, s);
            }
            return s;
        });
        return repo;
    }
}
