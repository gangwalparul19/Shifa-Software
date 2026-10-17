package com.shifa.oms.lead;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadSourceRequest;
import com.shifa.oms.lead.dto.LeadSourceResponse;
import com.shifa.oms.lead.dto.LeadTagRequest;
import com.shifa.oms.lead.dto.LeadTagResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Service tests for {@link LeadConfigService} (CRM expansion Phase 3, Task 19):
 * lead-tag CRUD (create/rename/retire + stage association) and lead-source CRUD
 * (create/rename/activate-deactivate), with unique-name enforcement. Only the
 * repository interfaces are mocked, backed by in-memory stores.
 */
class LeadConfigServiceTest {

    @Test
    void createTagIsCustomActiveAndStageScoped() {
        List<LeadTag> store = new ArrayList<>();
        LeadConfigService service = new LeadConfigService(tagRepo(store), sourceRepo(new ArrayList<>()));

        LeadTagResponse created = service.createTag(new LeadTagRequest("VIP", "green", true, 3, 42L));

        assertNotNull(created.id());
        assertEquals("VIP", created.name());
        assertEquals("CUSTOM", created.kind());
        assertTrue(created.active());
        assertEquals(42L, created.stageId());
    }

    @Test
    void createTagRejectsDuplicateNameCaseInsensitively() {
        List<LeadTag> store = new ArrayList<>();
        LeadConfigService service = new LeadConfigService(tagRepo(store), sourceRepo(new ArrayList<>()));
        service.createTag(new LeadTagRequest("Hot", null, true, 1, null));

        assertThrows(ValidationException.class,
                () -> service.createTag(new LeadTagRequest("hot", null, true, 2, null)));
    }

    @Test
    void retireTagKeepsItButInactive() {
        List<LeadTag> store = new ArrayList<>();
        LeadConfigService service = new LeadConfigService(tagRepo(store), sourceRepo(new ArrayList<>()));
        LeadTagResponse created = service.createTag(new LeadTagRequest("Cold", null, true, 1, null));

        LeadTagResponse retired = service.retireTag(created.id());

        assertFalse(retired.active());
        assertEquals(1, service.listAllTags().size(), "retire != delete");
    }

    @Test
    void createSourceIsUniqueAndActive() {
        List<ManagedLeadSource> store = new ArrayList<>();
        LeadConfigService service = new LeadConfigService(tagRepo(new ArrayList<>()), sourceRepo(store));

        LeadSourceResponse created = service.createSource(new LeadSourceRequest("Expo", true, 5));
        assertTrue(created.active());
        assertEquals("Expo", created.name());

        assertThrows(ValidationException.class,
                () -> service.createSource(new LeadSourceRequest("EXPO", true, 6)));
    }

    @Test
    void deactivateSourceDropsItFromTheActivePicker() {
        List<ManagedLeadSource> store = new ArrayList<>();
        LeadConfigService service = new LeadConfigService(tagRepo(new ArrayList<>()), sourceRepo(store));
        LeadSourceResponse created = service.createSource(new LeadSourceRequest("Expo", true, 1));

        service.updateSource(created.id(), new LeadSourceRequest("Expo", false, 1));

        assertTrue(service.listActiveSources().isEmpty(), "deactivated source not in active picker");
        assertEquals(1, service.listAllSources().size(), "still in the admin list");
    }

    // --- in-memory repository fakes ----------------------------------------

    private static LeadTagRepository tagRepo(List<LeadTag> store) {
        LeadTagRepository repo = mock(LeadTagRepository.class);
        AtomicLong seq = new AtomicLong(1);
        lenient().when(repo.save(any(LeadTag.class))).thenAnswer(inv -> {
            LeadTag tag = inv.getArgument(0);
            if (tag.getId() == null) {
                setId(tag, seq.getAndIncrement());
                store.add(tag);
            }
            return tag;
        });
        lenient().when(repo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(t -> id.equals(t.getId())).findFirst();
        });
        lenient().when(repo.findByNameIgnoreCase(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return store.stream().filter(t -> t.getName().equalsIgnoreCase(name)).findFirst();
        });
        lenient().when(repo.findAll()).thenReturn(store);
        return repo;
    }

    private static ManagedLeadSourceRepository sourceRepo(List<ManagedLeadSource> store) {
        ManagedLeadSourceRepository repo = mock(ManagedLeadSourceRepository.class);
        AtomicLong seq = new AtomicLong(1);
        lenient().when(repo.save(any(ManagedLeadSource.class))).thenAnswer(inv -> {
            ManagedLeadSource s = inv.getArgument(0);
            if (s.getId() == null) {
                setId(s, seq.getAndIncrement());
                store.add(s);
            }
            return s;
        });
        lenient().when(repo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(s -> id.equals(s.getId())).findFirst();
        });
        lenient().when(repo.findByNameIgnoreCase(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return store.stream().filter(s -> s.getName().equalsIgnoreCase(name)).findFirst();
        });
        lenient().when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(store);
        lenient().when(repo.findByActiveTrueOrderBySortOrderAscIdAsc()).thenAnswer(inv ->
                store.stream().filter(ManagedLeadSource::isActive).toList());
        return repo;
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
