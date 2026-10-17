package com.shifa.oms.task;

import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.task.dto.CreateTaskRequest;
import com.shifa.oms.task.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * Unit tests for {@link TaskService} (CRM expansion Phase 2, Task 10): lifecycle,
 * owner scoping (SALESPERSON own only), and activity recording. Mocks only the
 * {@link TaskRepository} interface + a mocked {@link UserRepository} behind a
 * real {@link SalespersonScopeResolver} (Java 25 gotcha). The reminder-idempotency
 * path is covered by {@link TaskReminderJobTest}.
 */
class TaskServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final AuthPrincipal sales = new AuthPrincipal(10L, "sales1", Role.SALESPERSON);
    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @Test
    void createStoresAnOwnedTaskAndRecordsActivity() {
        List<TaskEntity> store = new ArrayList<>();
        int[] activityCount = {0};
        TaskService service = new TaskService(repo(store), new SalespersonScopeResolver(),
                countingActivity(activityCount), CLOCK);

        TaskResponse created = service.create(new CreateTaskRequest(
                "Call Asha", "ring back", 5L, "9876543210", null, TaskPriority.HIGH, null), sales);

        assertThat(created.ownerUserId()).isEqualTo(10L);
        assertThat(created.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(created.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(store).hasSize(1);
        assertThat(activityCount[0]).isEqualTo(1); // TASK_CREATED recorded (linked to a lead)
    }

    @Test
    void completeMarksDoneAndSetsCompletionTime() {
        List<TaskEntity> store = new ArrayList<>();
        TaskService service = new TaskService(repo(store), new SalespersonScopeResolver(),
                null, CLOCK);
        TaskResponse created = service.create(new CreateTaskRequest(
                "Follow up", null, null, null, null, null, null), sales);

        TaskResponse done = service.complete(created.id(), null, sales);

        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(done.completedAt()).isEqualTo(LocalDateTime.now(CLOCK));
    }

    @Test
    void completeStoresTheCompletionNoteAndRecordsActivity() {
        List<TaskEntity> store = new ArrayList<>();
        int[] activity = {0};
        TaskService service = new TaskService(repo(store), new SalespersonScopeResolver(),
                countingActivity(activity), CLOCK);
        TaskResponse created = service.create(new CreateTaskRequest(
                "Follow up", null, 5L, null, null, null, null), sales);

        TaskResponse done = service.complete(created.id(), "  Customer confirmed order  ", sales);

        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(done.completionNote()).isEqualTo("Customer confirmed order"); // trimmed
        // TASK_CREATED + TASK_DONE (both have a lead link).
        assertThat(activity[0]).isEqualTo(2);
    }

    @Test
    void cancelSetsCancelledWithReasonAndTimeAndRecordsActivity() {
        List<TaskEntity> store = new ArrayList<>();
        int[] activity = {0};
        TaskService service = new TaskService(repo(store), new SalespersonScopeResolver(),
                countingActivity(activity), CLOCK);
        TaskResponse created = service.create(new CreateTaskRequest(
                "Chase quote", null, 5L, null, null, null, null), sales);

        TaskResponse cancelled = service.cancel(created.id(), "Customer went cold", sales);

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("Customer went cold");
        assertThat(cancelled.cancelledAt()).isEqualTo(LocalDateTime.now(CLOCK));
        // TASK_CREATED + TASK_CANCELLED (both have a lead link).
        assertThat(activity[0]).isEqualTo(2);
    }

    @Test
    void cancelOfACompletedTaskIsRejected() {
        List<TaskEntity> store = new ArrayList<>();
        TaskService service = new TaskService(repo(store), new SalespersonScopeResolver(),
                null, CLOCK);
        TaskResponse created = service.create(new CreateTaskRequest(
                "Done then cancel?", null, null, null, null, null, null), sales);
        service.complete(created.id(), null, sales);

        assertThatThrownBy(() -> service.cancel(created.id(), "too late", sales))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void salespersonCannotTouchAnotherUsersTask() {
        List<TaskEntity> store = new ArrayList<>();
        SalespersonScopeResolver resolver = new SalespersonScopeResolver();
        TaskService adminService = new TaskService(repo(store), resolver, null, CLOCK);
        // Admin creates a task owned by user 99.
        TaskResponse other = adminService.create(new CreateTaskRequest(
                "Owned by 99", null, null, null, null, null, 99L), admin);

        TaskService salesService = new TaskService(repo(store), resolver, null, CLOCK);
        assertThatThrownBy(() -> salesService.complete(other.id(), null, sales))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminSeesAllTasksSalespersonSeesOwn() {
        List<TaskEntity> store = new ArrayList<>();
        SalespersonScopeResolver resolver = new SalespersonScopeResolver();
        TaskService adminService = new TaskService(repo(store), resolver, null, CLOCK);
        adminService.create(new CreateTaskRequest("A", null, null, null, null, null, 10L), admin);
        adminService.create(new CreateTaskRequest("B", null, null, null, null, null, 99L), admin);

        TaskService salesService = new TaskService(repo(store), resolver, null, CLOCK);
        assertThat(salesService.list(null, sales)).hasSize(1);
        assertThat(adminService.list(null, admin)).hasSize(2);
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

    private static TaskRepository repo(List<TaskEntity> store) {
        TaskRepository repo = mock(TaskRepository.class);
        AtomicLong seq = new AtomicLong(100);
        lenient().when(repo.save(any(TaskEntity.class))).thenAnswer(inv -> {
            TaskEntity t = inv.getArgument(0);
            if (t.getId() == null) {
                setField(t, "id", seq.getAndIncrement());
            }
            if (store.stream().noneMatch(x -> x == t)) {
                store.add(t);
            }
            return t;
        });
        lenient().when(repo.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.stream().filter(t -> id.equals(t.getId())).findFirst();
        });
        lenient().when(repo.findScoped(nullable(java.util.Collection.class), nullable(TaskStatus.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.Collection<Long> owners = inv.getArgument(0);
                    TaskStatus status = inv.getArgument(1);
                    List<TaskEntity> out = new ArrayList<>();
                    for (TaskEntity t : store) {
                        if (owners != null && !owners.contains(t.getOwnerUserId())) {
                            continue;
                        }
                        if (status != null && t.getStatus() != status) {
                            continue;
                        }
                        out.add(t);
                    }
                    return out;
                });
        return repo;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = TaskEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
