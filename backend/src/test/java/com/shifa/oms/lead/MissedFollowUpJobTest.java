package com.shifa.oms.lead;

import com.shifa.oms.activity.ActivityRepository;
import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MissedFollowUpJob} (CRM expansion Phase 2, Task 12; Req
 * 10). Verifies the missed determination (reminded + no activity since the
 * reminder), the stamp, and the notify-assignee + escalate-to-team-lead + admin
 * fan-out. Only repository interfaces are mocked; the concrete publisher +
 * dispatcher are recording instances (Java 25 gotcha).
 */
class MissedFollowUpJobTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-02-10T04:00:00Z"), ZoneId.of("Asia/Kolkata"));

    @Test
    void marksMissedWhenRemindedAndNoActivitySinceReminderAndNotifiesAndEscalates() {
        LeadEntity lead = lead(1L, 10L, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 5));
        List<LeadEntity> store = new ArrayList<>(List.of(lead));

        LeadRepository leadRepo = leadRepo(store);
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        // No activity since the reminder → missed.
        lenient().when(activityRepo.existsByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(false);

        UserRepository userRepo = mock(UserRepository.class);
        User owner = new User("sales1", "x", Role.SALESPERSON, "Sales One", true);
        owner.setTeamLeadId(99L);
        when(userRepo.findById(10L)).thenReturn(Optional.of(owner));

        int[] userNotifs = {0};
        int[] roleNotifs = {0};
        MissedFollowUpJob job = new MissedFollowUpJob(
                leadRepo, activityRepo, null, recordingPublisher(),
                recordingDispatcher(userNotifs, roleNotifs), userRepo, CLOCK);
        job.setGraceHours(24);

        int missed = job.markMissedFollowUps();

        assertThat(missed).isEqualTo(1);
        assertThat(lead.getFollowUpMissedAt()).isNotNull();
        assertThat(userNotifs[0]).isEqualTo(2); // assignee + team lead
        assertThat(roleNotifs[0]).isEqualTo(1); // ADMIN
    }

    @Test
    void doesNotMarkMissedWhenActedOnSinceReminder() {
        LeadEntity lead = lead(2L, 10L, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 5));
        List<LeadEntity> store = new ArrayList<>(List.of(lead));

        LeadRepository leadRepo = leadRepo(store);
        ActivityRepository activityRepo = mock(ActivityRepository.class);
        when(activityRepo.existsByLeadIdAndCreatedAtAfter(anyLong(), any())).thenReturn(true);

        int[] userNotifs = {0};
        int[] roleNotifs = {0};
        MissedFollowUpJob job = new MissedFollowUpJob(
                leadRepo, activityRepo, null, recordingPublisher(),
                recordingDispatcher(userNotifs, roleNotifs), mock(UserRepository.class), CLOCK);

        int missed = job.markMissedFollowUps();

        assertThat(missed).isZero();
        assertThat(lead.getFollowUpMissedAt()).isNull();
        assertThat(userNotifs[0]).isZero();
    }

    @Test
    void wasActedOnSinceReminderIsPure() {
        assertThat(MissedFollowUpJob.wasActedOnSinceReminder(true)).isTrue();
        assertThat(MissedFollowUpJob.wasActedOnSinceReminder(false)).isFalse();
    }

    // --- harness ------------------------------------------------------------

    private static LeadEntity lead(long id, long owner, LocalDate followUp, LocalDate remindedOn) {
        LeadEntity l = new LeadEntity("Asha", LeadSource.WHATSAPP, owner);
        l.setStatus(LeadStatus.CONTACTED);
        l.setFollowUpDate(followUp);
        l.setRemindedOn(remindedOn);
        setLeadId(l, id);
        return l;
    }

    private static LeadRepository leadRepo(List<LeadEntity> store) {
        LeadRepository repo = mock(LeadRepository.class);
        lenient().when(repo.save(any(LeadEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findMissedCandidates(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate cutoff = inv.getArgument(0);
            List<LeadEntity> out = new ArrayList<>();
            for (LeadEntity l : store) {
                if (l.getFollowUpDate() != null
                        && l.getStatus() != LeadStatus.WON && l.getStatus() != LeadStatus.LOST
                        && l.getRemindedOn() != null && !l.getRemindedOn().isAfter(cutoff)
                        && l.getFollowUpMissedAt() == null) {
                    out.add(l);
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

    private static StaffNotificationDispatcher recordingDispatcher(int[] userNotifs, int[] roleNotifs) {
        // Pass null collaborators: the overridden dispatch methods never touch them,
        // and Mockito cannot mock the concrete WebPushService/AdminSseBroker under Java 25.
        return new StaffNotificationDispatcher(null, null, null) {
            @Override
            public AdminNotification dispatchToUser(String type, String title, String detail,
                    String severity, Long orderId, String orderCode, Long sourceEventId, Long userId) {
                userNotifs[0]++;
                return null;
            }

            @Override
            public AdminNotification dispatchToRole(String type, String title, String detail,
                    String severity, Long orderId, String orderCode, Long sourceEventId, Role role) {
                roleNotifs[0]++;
                return null;
            }
        };
    }

    private static void setLeadId(LeadEntity l, long id) {
        try {
            Field f = LeadEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(l, id);
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
