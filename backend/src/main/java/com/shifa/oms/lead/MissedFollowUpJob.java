package com.shifa.oms.lead;

import com.shifa.oms.activity.ActivityRepository;
import com.shifa.oms.activity.ActivityService;
import com.shifa.oms.activity.ActivityTypes;
import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Scheduled missed-follow-up job (CRM expansion Phase 2, Task 12; Req 10),
 * mirroring {@link FollowUpReminderJob}. After the configured grace window
 * ({@code app.crm.followup.grace-hours}, default 24) it evaluates leads that
 * were reminded about a due follow-up: for any that is still non-terminal, not
 * already missed, and has had <strong>no activity since the reminder</strong>
 * (checked against {@code crm_activities}), it stamps {@code follow_up_missed_at},
 * records a {@code FOLLOW_UP_MISSED} activity, notifies the assignee, and
 * escalates to the assignee's Team_Lead (via {@code users.team_lead_id}) and to
 * ADMIN (Req 10.2, 10.4).
 *
 * <p>Best-effort: a per-lead failure is logged and skipped so one bad lead never
 * stalls the rest; the stamp + notifications + escalation commit in one
 * transaction so a rolled-back run simply re-evaluates next time (idempotent via
 * the {@code follow_up_missed_at} stamp).
 */
@Component
public class MissedFollowUpJob {

    private static final Logger log = LoggerFactory.getLogger(MissedFollowUpJob.class);

    /** Staff-notification type discriminator for a missed follow-up. */
    static final String NOTIFICATION_TYPE = "LEAD_FOLLOW_UP_MISSED";

    private final LeadRepository leadRepository;
    private final ActivityRepository activityRepository;
    private final ActivityService activityService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final StaffNotificationDispatcher staffNotificationDispatcher;
    private final UserRepository userRepository;
    private final Clock clock;

    /** Hours after the reminder that an un-acted follow-up becomes Missed. */
    @Value("${app.crm.followup.grace-hours:24}")
    private long graceHours = 24;

    @Autowired
    public MissedFollowUpJob(LeadRepository leadRepository,
                             ActivityRepository activityRepository,
                             ActivityService activityService,
                             OutboxEventPublisher outboxEventPublisher,
                             StaffNotificationDispatcher staffNotificationDispatcher,
                             UserRepository userRepository) {
        this(leadRepository, activityRepository, activityService, outboxEventPublisher,
                staffNotificationDispatcher, userRepository, Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock. */
    public MissedFollowUpJob(LeadRepository leadRepository,
                             ActivityRepository activityRepository,
                             ActivityService activityService,
                             OutboxEventPublisher outboxEventPublisher,
                             StaffNotificationDispatcher staffNotificationDispatcher,
                             UserRepository userRepository,
                             Clock clock) {
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.activityRepository = Objects.requireNonNull(activityRepository, "activityRepository");
        this.activityService = activityService;
        this.outboxEventPublisher =
                Objects.requireNonNull(outboxEventPublisher, "outboxEventPublisher");
        this.staffNotificationDispatcher =
                Objects.requireNonNull(staffNotificationDispatcher, "staffNotificationDispatcher");
        this.userRepository = userRepository;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Test seam for the default grace window. */
    public void setGraceHours(long graceHours) {
        this.graceHours = graceHours;
    }

    /**
     * Whether a reminded follow-up was "acted on" (Req 10.2): true when there is
     * at least one activity on the lead after {@code reminderMoment}. Pure so it
     * is directly unit-testable; the job passes the crm_activities existence
     * signal.
     */
    public static boolean wasActedOnSinceReminder(boolean hasActivityAfterReminder) {
        return hasActivityAfterReminder;
    }

    /**
     * Evaluate missed follow-ups once (scheduled daily by default). Returns the
     * number of leads newly marked Missed.
     */
    @Scheduled(cron = "${app.crm.followup.missed.cron:0 30 9 * * *}")
    @Transactional
    public int markMissedFollowUps() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime graceCutoff = now.minusHours(graceHours);
        // A lead qualifies only if it was reminded on or before the grace cutoff's
        // date (the reminder fired at least graceHours ago).
        LocalDate remindedCutoff = graceCutoff.toLocalDate();
        List<LeadEntity> candidates = leadRepository.findMissedCandidates(remindedCutoff);
        int missed = 0;
        for (LeadEntity lead : candidates) {
            try {
                if (evaluateOne(lead, now, graceCutoff)) {
                    missed++;
                }
            } catch (RuntimeException e) {
                log.warn("Missed-follow-up evaluation failed for lead {}: {}",
                        lead.getId(), e.getMessage());
            }
        }
        if (missed > 0) {
            log.info("Missed-follow-up job marked {} lead(s) missed at {}", missed, now);
        }
        return missed;
    }

    /** Returns true when the lead was newly marked Missed. */
    private boolean evaluateOne(LeadEntity lead, LocalDateTime now, LocalDateTime graceCutoff) {
        // The reminder moment: start of the reminded_on day (reminders fire once
        // per due day). We treat "acted on" as any activity after that moment.
        LocalDate remindedOn = lead.getRemindedOn();
        if (remindedOn == null) {
            return false;
        }
        LocalDateTime reminderMoment = remindedOn.atStartOfDay();
        boolean hasActivity =
                activityRepository.existsByLeadIdAndCreatedAtAfter(lead.getId(), reminderMoment);
        if (wasActedOnSinceReminder(hasActivity)) {
            return false; // acted on within/after the grace window — not missed
        }

        lead.setFollowUpMissedAt(now);
        leadRepository.save(lead);

        String followUp = lead.getFollowUpDate() == null ? null : lead.getFollowUpDate().toString();
        OutboxEvent event = outboxEventPublisher.publishLeadFollowUpMissed(
                lead.getId(), lead.getCustomerName(), lead.getOwnerUserId(), followUp);

        if (activityService != null) {
            activityService.record(ActivityTypes.FOLLOW_UP_MISSED, lead.getId(),
                    lead.getCustomerMobile(), null, null,
                    "Follow-up missed (no action within grace window)",
                    followUp == null ? null : "was due " + followUp);
        }

        notifyAssigneeAndEscalate(lead, event.getId(), followUp);
        return true;
    }

    private void notifyAssigneeAndEscalate(LeadEntity lead, Long eventId, String followUp) {
        String detail = "Follow-up missed for lead \"" + lead.getCustomerName() + "\""
                + (followUp == null ? "" : " (was due " + followUp + ")") + ".";

        // The assignee (owner) — warning severity since it needs recovery.
        staffNotificationDispatcher.dispatchToUser(
                NOTIFICATION_TYPE, "Follow-up missed", detail,
                AdminNotification.SEVERITY_WARNING, null, null, eventId, lead.getOwnerUserId());

        // Escalate to the assignee's team lead (via users.team_lead_id), if any.
        if (userRepository != null && lead.getOwnerUserId() != null) {
            User owner = userRepository.findById(lead.getOwnerUserId()).orElse(null);
            if (owner != null && owner.getTeamLeadId() != null) {
                staffNotificationDispatcher.dispatchToUser(
                        NOTIFICATION_TYPE, "Team follow-up missed",
                        "A team member missed a follow-up: " + detail,
                        AdminNotification.SEVERITY_WARNING, null, null, null, owner.getTeamLeadId());
            }
        }

        // Escalate to ADMIN (role-addressed, single shared row).
        staffNotificationDispatcher.dispatchToRole(
                NOTIFICATION_TYPE, "Follow-up missed",
                detail + " Assignee: user " + lead.getOwnerUserId() + ".",
                AdminNotification.SEVERITY_WARNING, null, null, null, Role.ADMIN);
    }
}
