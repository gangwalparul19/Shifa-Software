package com.shifa.oms.meeting;

import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.meeting.dto.MeetingOutcomeRequest;
import com.shifa.oms.meeting.dto.MeetingResponse;
import com.shifa.oms.meeting.dto.ScheduleMeetingRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link MeetingService} (CRM expansion Phase 2, Task 11):
 * schedule/reschedule/cancel/outcome lifecycle, owner scoping, and activity
 * recording. Only the {@link MeetingRepository} interface is mocked; the
 * {@link SalespersonScopeResolver} is real (Java 25 gotcha).
 */
class MeetingServiceTest {

    private final AuthPrincipal sales = new AuthPrincipal(10L, "sales1", Role.SALESPERSON);
    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @Test
    void scheduleStoresAnOwnedMeetingAndRecordsActivity() {
        List<MeetingEntity> store = new ArrayList<>();
        int[] activity = {0};
        MeetingService service = new MeetingService(repo(store), new SalespersonScopeResolver(),
                countingActivity(activity));

        MeetingResponse m = service.schedule(new ScheduleMeetingRequest(
                "Demo", 5L, "9876543210", LocalDateTime.now().plusHours(2),
                MeetingMode.VIDEO, "Zoom", "intro", null), sales);

        assertThat(m.ownerUserId()).isEqualTo(10L);
        assertThat(m.status()).isEqualTo(MeetingStatus.SCHEDULED);
        assertThat(m.mode()).isEqualTo(MeetingMode.VIDEO);
        assertThat(activity[0]).isEqualTo(1);
    }

    @Test
    void recordOutcomeMarksDone() {
        List<MeetingEntity> store = new ArrayList<>();
        MeetingService service = new MeetingService(repo(store), new SalespersonScopeResolver(), null);
        MeetingResponse m = service.schedule(new ScheduleMeetingRequest(
                "Call", null, null, LocalDateTime.now().plusHours(1), null, null, null, null), sales);

        MeetingResponse done = service.recordOutcome(
                m.id(), new MeetingOutcomeRequest("Agreed to buy"), sales);

        assertThat(done.status()).isEqualTo(MeetingStatus.DONE);
        assertThat(done.outcome()).isEqualTo("Agreed to buy");
    }

    @Test
    void salespersonCannotTouchAnotherUsersMeeting() {
        List<MeetingEntity> store = new ArrayList<>();
        SalespersonScopeResolver resolver = new SalespersonScopeResolver();
        MeetingService adminService = new MeetingService(repo(store), resolver, null);
        MeetingResponse other = adminService.schedule(new ScheduleMeetingRequest(
                "Owned by 99", null, null, LocalDateTime.now().plusHours(1), null, null, null, 99L),
                admin);

        MeetingService salesService = new MeetingService(repo(store), resolver, null);
        assertThatThrownBy(() -> salesService.cancel(other.id(), sales))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- harness ------------------------------------------------------------

    private static ActivityService countingActivity(int[] counter) {
        return new ActivityService(mock(com.shifa.oms.activity.ActivityRepository.class)) {
            @Override
            public com.shifa.oms.activity.ActivityEntry record(
                    String type, Long leadId, String customerMobile,
                    AuthPrincipal actor, String summary, String detail) {
                counter[0]++;
                return null;
            }
        };
    }

    private static MeetingRepository repo(List<MeetingEntity> store) {
        MeetingRepository repo = mock(MeetingRepository.class);
        AtomicLong seq = new AtomicLong(200);
        lenient().when(repo.save(any(MeetingEntity.class))).thenAnswer(inv -> {
            MeetingEntity m = inv.getArgument(0);
            if (m.getId() == null) {
                setField(m, "id", seq.getAndIncrement());
            }
            if (store.stream().noneMatch(x -> x == m)) {
                store.add(m);
            }
            return m;
        });
        lenient().when(repo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(m -> id.equals(m.getId())).findFirst();
        });
        lenient().when(repo.findScoped(nullable(java.util.Collection.class),
                nullable(MeetingStatus.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Collection<Long> owners = inv.getArgument(0);
            MeetingStatus status = inv.getArgument(1);
            List<MeetingEntity> out = new ArrayList<>();
            for (MeetingEntity m : store) {
                if (owners != null && !owners.contains(m.getOwnerUserId())) {
                    continue;
                }
                if (status != null && m.getStatus() != status) {
                    continue;
                }
                out.add(m);
            }
            return out;
        });
        return repo;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = MeetingEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
