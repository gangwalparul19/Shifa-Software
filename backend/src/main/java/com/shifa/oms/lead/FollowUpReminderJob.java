package com.shifa.oms.lead;

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
import java.util.List;
import java.util.Objects;

/**
 * Scheduled follow-up reminder job (Req 5.3, 5.5; design &sect;Follow-up
 * Reminders). On its configured schedule it finds non-terminal leads whose
 * {@code follow_up_date} is due (on or before today) and which have not yet been
 * reminded today, and for each enqueues an in-app reminder addressed to the lead
 * <em>owner</em> via the transactional outbox +
 * {@link StaffNotificationDispatcher}, then stamps {@code reminded_on = today} so
 * a lead is reminded at most once per due day (idempotent per due-day).
 *
 * <p>The synchronous counterpart the dashboard/badge reads is
 * {@link LeadService#dueFollowUps} / {@code GET /api/leads/follow-ups/due}.
 *
 * <p>The firing schedule comes from {@code app.lead.reminder.cron} (default
 * {@code 0 0 9 * * *} — daily at 09:00). The job is best-effort: a per-lead
 * failure is logged and skipped so one bad lead never stalls the rest, and an
 * unexpected error never escapes the scheduler thread. Because the outbox row,
 * the in-app notification, and the {@code reminded_on} stamp commit in the same
 * transaction, a rolled-back run simply re-fires next time (nothing is lost, and
 * a lead is never double-reminded within a day).
 */
@Component
public class FollowUpReminderJob {

    private static final Logger log = LoggerFactory.getLogger(FollowUpReminderJob.class);

    /** Staff-notification type discriminator for a due lead follow-up. */
    static final String NOTIFICATION_TYPE = "LEAD_FOLLOW_UP_DUE";

    private final LeadRepository leadRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final StaffNotificationDispatcher staffNotificationDispatcher;
    private final Clock clock;

    /** Production constructor (Spring): uses the system default-zone clock for "today". */
    @Autowired
    public FollowUpReminderJob(LeadRepository leadRepository,
                               OutboxEventPublisher outboxEventPublisher,
                               StaffNotificationDispatcher staffNotificationDispatcher) {
        this(leadRepository, outboxEventPublisher, staffNotificationDispatcher,
                Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock (deterministic "today"). */
    public FollowUpReminderJob(LeadRepository leadRepository,
                               OutboxEventPublisher outboxEventPublisher,
                               StaffNotificationDispatcher staffNotificationDispatcher,
                               Clock clock) {
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.outboxEventPublisher =
                Objects.requireNonNull(outboxEventPublisher, "outboxEventPublisher");
        this.staffNotificationDispatcher =
                Objects.requireNonNull(staffNotificationDispatcher, "staffNotificationDispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Scheduled entry point: enqueue in-app reminders for every due, not-yet-
     * reminded-today, non-terminal lead and stamp each as reminded today. Runs in
     * one transaction so the enqueue + stamp commit atomically; per-lead failures
     * are contained so the run continues. Returns the number of reminders sent
     * (useful for tests / logging).
     */
    @Scheduled(cron = "${app.lead.reminder.cron:0 0 9 * * *}")
    @Transactional
    public int remindDueFollowUps() {
        LocalDate today = LocalDate.now(clock);
        List<LeadEntity> due = leadRepository.findDueForReminder(today);
        int reminded = 0;
        for (LeadEntity lead : due) {
            try {
                remindOne(lead, today);
                reminded++;
            } catch (RuntimeException e) {
                log.warn("Follow-up reminder failed for lead {}: {}",
                        lead.getId(), e.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("Follow-up reminder job sent {} reminder(s) for {}", reminded, today);
        }
        return reminded;
    }

    /** Enqueues one owner-addressed reminder for {@code lead} and marks it reminded today. */
    private void remindOne(LeadEntity lead, LocalDate today) {
        String followUp = lead.getFollowUpDate() == null ? null : lead.getFollowUpDate().toString();
        OutboxEvent event = outboxEventPublisher.publishLeadFollowUpDue(
                lead.getId(), lead.getCustomerName(), lead.getOwnerUserId(), followUp);

        String detail = "Follow-up due for lead \"" + lead.getCustomerName() + "\""
                + (followUp == null ? "" : " (due " + followUp + ")") + ".";
        // Addressed to the lead owner (recipient = the owner user id); lead-scoped,
        // so the order fields are null. The outbox event id de-dups the write.
        staffNotificationDispatcher.dispatchToUser(
                NOTIFICATION_TYPE,
                "Lead follow-up due",
                detail,
                AdminNotification.SEVERITY_INFO,
                null,
                null,
                event.getId(),
                lead.getOwnerUserId());

        lead.setRemindedOn(today);
        leadRepository.save(lead);
    }
}
