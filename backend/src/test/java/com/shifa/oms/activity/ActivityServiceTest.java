package com.shifa.oms.activity;

import com.shifa.oms.activity.dto.ActivityResponse;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the unified {@link ActivityService} (CRM expansion Phase 2,
 * Task 9): record-on-touch, lead vs customer keying, and newest-first ordering
 * (Req 11.1, 11.2). The {@link ActivityRepository} <em>interface</em> is mocked
 * and answered from an in-memory store (Java 25 gotcha: only interfaces mocked).
 */
class ActivityServiceTest {

    private final AuthPrincipal actor = new AuthPrincipal(7L, "asha", Role.SALESPERSON);

    @Test
    void recordWritesAnEntryKeyedToTheLead() {
        List<ActivityEntry> store = new ArrayList<>();
        ActivityService service = new ActivityService(repo(store));

        service.record(ActivityTypes.STAGE_CHANGE, 5L, "9876543210", actor, "Stage NEW -> CONTACTED", null);

        assertThat(store).hasSize(1);
        ActivityEntry e = store.get(0);
        assertThat(e.getActivityType()).isEqualTo(ActivityTypes.STAGE_CHANGE);
        assertThat(e.getLeadId()).isEqualTo(5L);
        assertThat(e.getCustomerMobile()).isEqualTo("9876543210");
        assertThat(e.getActorUserId()).isEqualTo(7L);
        assertThat(e.getActorName()).isEqualTo("asha");
    }

    @Test
    void timelineForLeadReturnsOnlyThatLeadsRowsNewestFirst() {
        List<ActivityEntry> store = new ArrayList<>();
        ActivityService service = new ActivityService(repo(store));

        service.record(ActivityTypes.STAGE_CHANGE, 5L, null, actor, "older", null);
        service.record(ActivityTypes.TAG_ADDED, 5L, null, actor, "newer", null);
        service.record(ActivityTypes.STAGE_CHANGE, 6L, null, actor, "other lead", null);

        List<ActivityResponse> timeline = service.timelineForLead(5L);

        assertThat(timeline).extracting(ActivityResponse::summary).containsExactly("newer", "older");
    }

    @Test
    void timelineForCustomerIsKeyedByMobileNewestFirst() {
        List<ActivityEntry> store = new ArrayList<>();
        ActivityService service = new ActivityService(repo(store));

        service.record(ActivityTypes.CALL, null, "9000000001", actor, "called first", null);
        service.record(ActivityTypes.WHATSAPP, null, "9000000001", actor, "messaged after", null);
        service.record(ActivityTypes.CALL, null, "9999999999", actor, "different customer", null);

        List<ActivityResponse> timeline = service.timelineForCustomer("9000000001");

        assertThat(timeline).extracting(ActivityResponse::summary)
                .containsExactly("messaged after", "called first");
    }

    @Test
    void recordNeverThrowsWhenRepositoryFails() {
        ActivityRepository failing = mock(ActivityRepository.class);
        lenient().when(failing.save(any())).thenThrow(new RuntimeException("db down"));
        ActivityService service = new ActivityService(failing);

        // Must not propagate — the timeline write is best-effort.
        assertThat(service.record(ActivityTypes.NOTE, 1L, null, actor, "note", null)).isNull();
    }

    // --- in-memory repo harness --------------------------------------------

    private static ActivityRepository repo(List<ActivityEntry> store) {
        ActivityRepository repo = mock(ActivityRepository.class);
        AtomicLong seq = new AtomicLong(1);
        lenient().when(repo.save(any(ActivityEntry.class))).thenAnswer(inv -> {
            ActivityEntry e = inv.getArgument(0);
            setField(e, "id", seq.getAndIncrement());
            setField(e, "createdAt", LocalDateTime.now().plusNanos(seq.get()));
            store.add(e);
            return e;
        });
        lenient().when(repo.findByLeadIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> {
            Long leadId = inv.getArgument(0);
            List<ActivityEntry> out = new ArrayList<>(store.stream()
                    .filter(e -> leadId.equals(e.getLeadId())).toList());
            out.sort(Comparator.comparing(ActivityEntry::getCreatedAt).reversed());
            return out;
        });
        lenient().when(repo.findByCustomerMobileOrderByCreatedAtDesc(anyString())).thenAnswer(inv -> {
            String mobile = inv.getArgument(0);
            List<ActivityEntry> out = new ArrayList<>(store.stream()
                    .filter(e -> mobile.equals(e.getCustomerMobile())).toList());
            out.sort(Comparator.comparing(ActivityEntry::getCreatedAt).reversed());
            return out;
        });
        return repo;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = ActivityEntry.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
