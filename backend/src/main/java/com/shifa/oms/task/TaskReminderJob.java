package com.shifa.oms.task;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Scheduled task-reminder job (CRM expansion Phase 2, Task 10; Req 8.4, 15.2),
 * mirroring {@link com.shifa.oms.lead.FollowUpReminderJob}. On its schedule it
 * finds OPEN tasks whose {@code due_at} has arrived and which have not yet been
 * reminded today, and for each enqueues an in-app reminder addressed to the task
 * <em>owner</em> via the transactional outbox + {@link StaffNotificationDispatcher},
 * then stamps {@code reminded_on = today} (idempotent per due day).
 *
 * <p>The schedule comes from {@code app.crm.task.reminder.cron} (default every
 * 15 minutes). Best-effort: a per-task failure is logged and skipped so one bad
 * task never stalls the rest, and the enqueue + stamp commit together so a
 * rolled-back run simply re-fires next time.
 */
@Component
public class TaskReminderJob {

    private static final Logger log = LoggerFactory.getLogger(TaskReminderJob.class);

    /** Staff-notification type discriminator for a due task. */
    static final String NOTIFICATION_TYPE = "TASK_DUE";

    private final TaskRepository taskRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final StaffNotificationDispatcher staffNotificationDispatcher;
    private final Clock clock;

    @Autowired
    public TaskReminderJob(TaskRepository taskRepository,
                           OutboxEventPublisher outboxEventPublisher,
                           StaffNotificationDispatcher staffNotificationDispatcher) {
        this(taskRepository, outboxEventPublisher, staffNotificationDispatcher,
                Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock (deterministic "now"/"today"). */
    public TaskReminderJob(TaskRepository taskRepository,
                           OutboxEventPublisher outboxEventPublisher,
                           StaffNotificationDispatcher staffNotificationDispatcher,
                           Clock clock) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.outboxEventPublisher =
                Objects.requireNonNull(outboxEventPublisher, "outboxEventPublisher");
        this.staffNotificationDispatcher =
                Objects.requireNonNull(staffNotificationDispatcher, "staffNotificationDispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Enqueue owner-addressed reminders for every due, not-yet-reminded-today,
     * OPEN task and stamp each reminded today. One transaction; per-task failures
     * are contained. Returns the number of reminders sent.
     */
    @Scheduled(cron = "${app.crm.task.reminder.cron:0 0/15 * * * *}")
    @Transactional
    public int remindDueTasks() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);
        List<TaskEntity> due = taskRepository.findDueForReminder(now, today);
        int reminded = 0;
        for (TaskEntity task : due) {
            try {
                remindOne(task, today);
                reminded++;
            } catch (RuntimeException e) {
                log.warn("Task reminder failed for task {}: {}", task.getId(), e.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("Task reminder job sent {} reminder(s) for {}", reminded, today);
        }
        return reminded;
    }

    private void remindOne(TaskEntity task, LocalDate today) {
        String dueAt = task.getDueAt() == null ? null : task.getDueAt().toString();
        OutboxEvent event = outboxEventPublisher.publishTaskDue(
                task.getId(), task.getTitle(), task.getOwnerUserId(), dueAt);

        String detail = "Task \"" + task.getTitle() + "\" is due"
                + (dueAt == null ? "" : " (" + dueAt + ")") + ".";
        staffNotificationDispatcher.dispatchToUser(
                NOTIFICATION_TYPE,
                "Task due",
                detail,
                AdminNotification.SEVERITY_INFO,
                null,
                null,
                event.getId(),
                task.getOwnerUserId());

        task.setRemindedOn(today);
        taskRepository.save(task);
    }
}
