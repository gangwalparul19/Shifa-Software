package com.shifa.oms.notification;

import com.shifa.oms.mail.MailException;
import com.shifa.oms.mail.MailMessage;
import com.shifa.oms.mail.MailProperties;
import com.shifa.oms.mail.MailService;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the email outbox delivery lifecycle (design §5.2,
 * §Correctness Properties (20)).
 *
 * Feature: role-based-order-workflow, Property 20: The outbox delivery lifecycle
 * is safe and retryable.
 *
 * **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5**
 *
 * <p>In-memory: the real {@link MailNotificationPublisher} + {@link EmailOutboxDrainer}
 * wired over a mocked {@link OutboxEventRepository} interface and a
 * <em>recording</em> {@link MailService} (a real instance, not a concrete-class
 * mock — Java 25). For any {@code maxAttempts} and any number of failures before a
 * success, the property drives the drainer repeatedly and asserts: the event is
 * enqueued {@code PENDING} in the same transaction; a failed attempt keeps it
 * {@code PENDING} with {@code attempts} incremented and {@code last_error}
 * recorded; a success marks it {@code SENT} once and it is never sent again; and
 * exhausting {@code maxAttempts} marks it {@code FAILED} and raises an
 * {@code EMAIL_FAILED} ADMIN alert. Each {@code @Property} runs the jqwik default
 * of 1000 tries (≥ 100).
 */
class EmailOutboxLifecyclePropertyTest {

    // Feature: role-based-order-workflow, Property 20: The outbox delivery lifecycle is safe and retryable
    // **Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5**
    @Property
    void lifecycleIsSafeAndRetryable(@ForAll @IntRange(min = 1, max = 5) int maxAttempts,
                                     @ForAll @IntRange(min = 0, max = 6) int failuresBeforeSuccess) {
        List<OutboxEvent> saved = new ArrayList<>();
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.save(any(OutboxEvent.class))).thenAnswer(i -> {
            OutboxEvent e = i.getArgument(0);
            if (!saved.contains(e)) {
                saved.add(e);
            }
            return e;
        });
        OutboxEventPublisher publisher = new OutboxEventPublisher(repo);
        MailNotificationPublisher mailPublisher = new MailNotificationPublisher(publisher);

        // Enqueue a milestone email — it must be PENDING in the enqueuing txn (Req 14.1).
        mailPublisher.enqueue(60L, "SHR-000060", NotificationEvent.DELIVERED,
                "asha@example.com", "Asha", 7L);
        OutboxEvent notify = saved.get(0);
        assertThat(notify.getEventType()).isEqualTo(OutboxEvent.EVENT_EMAIL_NOTIFY);
        assertThat(notify.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);

        // findDue returns the event only while it is still PENDING.
        when(repo.findDue(any(), any(), any())).thenAnswer(i ->
                OutboxEvent.STATUS_PENDING.equals(notify.getStatus()) ? List.of(notify) : List.of());

        RecordingMailService sender = new RecordingMailService(failuresBeforeSuccess);
        MailProperties props = new MailProperties(
                "MOCK", null, null, null, null, maxAttempts, Duration.ZERO);
        EmailOutboxDrainer drainer = new EmailOutboxDrainer(repo, publisher, sender, props);

        // Drain enough rounds to reach a terminal outcome (each round sends at most once).
        for (int round = 0; round < maxAttempts + 2; round++) {
            drainer.drainNotifications();
        }

        boolean succeeds = failuresBeforeSuccess < maxAttempts;
        long failedAlerts = saved.stream()
                .filter(e -> OutboxEvent.EVENT_EMAIL_FAILED.equals(e.getEventType()))
                .count();

        if (succeeds) {
            // Marked SENT once; sent exactly failuresBeforeSuccess+1 times; never again.
            assertThat(notify.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
            assertThat(sender.sends).isEqualTo(failuresBeforeSuccess + 1);
            assertThat(notify.getAttempts()).isEqualTo(failuresBeforeSuccess + 1);
            assertThat(failedAlerts).isZero();
        } else {
            // Exhausted: FAILED after exactly maxAttempts, last_error recorded, one ADMIN alert.
            assertThat(notify.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
            assertThat(sender.sends).isEqualTo(maxAttempts);
            assertThat(notify.getAttempts()).isEqualTo(maxAttempts);
            assertThat(notify.getLastError()).isNotBlank();
            assertThat(failedAlerts).isEqualTo(1);
        }
    }

    /**
     * A recording {@link MailService} that fails its first {@code failures} sends
     * then succeeds. A real instance (never a Mockito mock of a concrete class,
     * per the Java 25 runtime gotcha).
     */
    private static final class RecordingMailService implements MailService {
        private int failuresRemaining;
        int sends;

        RecordingMailService(int failures) {
            this.failuresRemaining = failures;
        }

        @Override
        public void send(MailMessage message) {
            sends++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new MailException("Simulated email failure");
            }
        }
    }
}
