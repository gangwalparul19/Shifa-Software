package com.shifa.oms.task;

import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TaskReminderJob} (CRM expansion Phase 2, Task 10): a due
 * OPEN task is reminded once, the owner is notified, and it is not reminded
 * again the same day (idempotent via {@code reminded_on}). Repository interface
 * mocked; the concrete {@link OutboxEventPublisher} and
 * {@link StaffNotificationDispatcher} are real/recording per the Java 25 gotcha.
 */
class TaskReminderJobTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @Test
    void remindsDueTaskOnceAndStampsRemindedOn() {
        List<TaskEntity> store = new ArrayList<>();
        TaskEntity due = task(1L, 10L, LocalDateTime.now(CLOCK).minusHours(1));
        store.add(due);

        int[] notified = {0};
        TaskReminderJob job = new TaskReminderJob(
                repo(store), recordingPublisher(), recordingDispatcher(notified), CLOCK);

        int first = job.remindDueTasks();
        assertThat(first).isEqualTo(1);
        assertThat(notified[0]).isEqualTo(1);
        assertThat(due.getRemindedOn()).isEqualTo(LocalDate.now(CLOCK));

        // Second run the same day: the stamped task is no longer "due for reminder".
        int second = job.remindDueTasks();
        assertThat(second).isZero();
        assertThat(notified[0]).isEqualTo(1);
    }

    // --- harness ------------------------------------------------------------

    private static TaskEntity task(long id, long owner, LocalDateTime dueAt) {
        TaskEntity t = new TaskEntity(owner, "Call back");
        t.setDueAt(dueAt);
        setField(t, "id", id);
        return t;
    }

    private static TaskRepository repo(List<TaskEntity> store) {
        TaskRepository repo = mock(TaskRepository.class);
        lenient().when(repo.save(any(TaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findDueForReminder(any(LocalDateTime.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDateTime now = inv.getArgument(0);
                    LocalDate today = inv.getArgument(1);
                    List<TaskEntity> out = new ArrayList<>();
                    for (TaskEntity t : store) {
                        if (t.getStatus() == TaskStatus.OPEN
                                && t.getDueAt() != null && !t.getDueAt().isAfter(now)
                                && (t.getRemindedOn() == null || !t.getRemindedOn().equals(today))) {
                            out.add(t);
                        }
                    }
                    return out;
                });
        return repo;
    }

    private static OutboxEventPublisher recordingPublisher() {
        var repo = mock(com.shifa.oms.platform.outbox.OutboxEventRepository.class);
        lenient().when(repo.save(any(OutboxEvent.class))).thenAnswer(inv -> {
            OutboxEvent e = inv.getArgument(0);
            setOutboxId(e, 1L);
            return e;
        });
        return new OutboxEventPublisher(repo);
    }

    private static StaffNotificationDispatcher recordingDispatcher(int[] notified) {
        // Pass null collaborators: the overridden dispatch method never touches them,
        // and Mockito cannot mock the concrete WebPushService/AdminSseBroker under Java 25.
        return new StaffNotificationDispatcher(null, null, null) {
            @Override
            public com.shifa.oms.adminnotification.AdminNotification dispatchToUser(
                    String type, String title, String detail, String severity, Long orderId,
                    String orderCode, Long sourceEventId, Long userId) {
                notified[0]++;
                return null;
            }
        };
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

    private static void setOutboxId(OutboxEvent e, long id) {
        try {
            Field f = OutboxEvent.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
