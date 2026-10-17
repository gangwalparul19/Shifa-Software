package com.shifa.oms.meeting;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
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
 * Scheduled meeting-reminder job (CRM expansion Phase 2, Task 11; Req 9.3,
 * 15.2), mirroring {@link com.shifa.oms.lead.FollowUpReminderJob}. On its
 * schedule it finds SCHEDULED meetings starting within the lookahead window
 * ({@code app.crm.meeting.reminder.lookahead-minutes}, default 60) that have not
 * yet been reminded today and, for each, enqueues an in-app reminder to the
 * meeting <em>owner</em> via the transactional outbox +
 * {@link StaffNotificationDispatcher}, then stamps {@code reminded_on = today}.
 *
 * <p>Best-effort: per-meeting failures are contained; the schedule is
 * {@code app.crm.meeting.reminder.cron} (default every 15 minutes).
 */
@Component
public class MeetingReminderJob {

    private static final Logger log = LoggerFactory.getLogger(MeetingReminderJob.class);

    /** Staff-notification type discriminator for an upcoming meeting. */
    static final String NOTIFICATION_TYPE = "MEETING_UPCOMING";

    private final MeetingRepository meetingRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final StaffNotificationDispatcher staffNotificationDispatcher;
    private final Clock clock;

    /** Minutes ahead of "now" a SCHEDULED meeting is considered "upcoming". */
    @Value("${app.crm.meeting.reminder.lookahead-minutes:60}")
    private long lookaheadMinutes = 60;

    @Autowired
    public MeetingReminderJob(MeetingRepository meetingRepository,
                              OutboxEventPublisher outboxEventPublisher,
                              StaffNotificationDispatcher staffNotificationDispatcher) {
        this(meetingRepository, outboxEventPublisher, staffNotificationDispatcher,
                Clock.systemDefaultZone());
    }

    /** Test constructor with a fixed clock (deterministic "now"/"today"). */
    public MeetingReminderJob(MeetingRepository meetingRepository,
                              OutboxEventPublisher outboxEventPublisher,
                              StaffNotificationDispatcher staffNotificationDispatcher,
                              Clock clock) {
        this.meetingRepository = Objects.requireNonNull(meetingRepository, "meetingRepository");
        this.outboxEventPublisher =
                Objects.requireNonNull(outboxEventPublisher, "outboxEventPublisher");
        this.staffNotificationDispatcher =
                Objects.requireNonNull(staffNotificationDispatcher, "staffNotificationDispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Sets the lookahead window (minutes). Test seam for the default @Value. */
    public void setLookaheadMinutes(long lookaheadMinutes) {
        this.lookaheadMinutes = lookaheadMinutes;
    }

    /**
     * Enqueue owner-addressed reminders for every upcoming, not-yet-reminded-today
     * SCHEDULED meeting and stamp each reminded today. Returns the number sent.
     */
    @Scheduled(cron = "${app.crm.meeting.reminder.cron:0 0/15 * * * *}")
    @Transactional
    public int remindUpcomingMeetings() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowEnd = now.plusMinutes(lookaheadMinutes);
        LocalDate today = LocalDate.now(clock);
        List<MeetingEntity> upcoming =
                meetingRepository.findUpcomingForReminder(now, windowEnd, today);
        int reminded = 0;
        for (MeetingEntity meeting : upcoming) {
            try {
                remindOne(meeting, today);
                reminded++;
            } catch (RuntimeException e) {
                log.warn("Meeting reminder failed for meeting {}: {}",
                        meeting.getId(), e.getMessage());
            }
        }
        if (reminded > 0) {
            log.info("Meeting reminder job sent {} reminder(s) for {}", reminded, today);
        }
        return reminded;
    }

    private void remindOne(MeetingEntity meeting, LocalDate today) {
        String startAt = meeting.getStartAt() == null ? null : meeting.getStartAt().toString();
        OutboxEvent event = outboxEventPublisher.publishMeetingUpcoming(
                meeting.getId(), meeting.getTitle(), meeting.getOwnerUserId(), startAt);

        String detail = "Upcoming meeting \"" + meeting.getTitle() + "\""
                + (startAt == null ? "" : " at " + startAt) + ".";
        staffNotificationDispatcher.dispatchToUser(
                NOTIFICATION_TYPE,
                "Upcoming meeting",
                detail,
                AdminNotification.SEVERITY_INFO,
                null,
                null,
                event.getId(),
                meeting.getOwnerUserId());

        meeting.setRemindedOn(today);
        meetingRepository.save(meeting);
    }
}
