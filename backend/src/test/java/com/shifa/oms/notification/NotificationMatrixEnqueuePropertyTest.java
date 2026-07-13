package com.shifa.oms.notification;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.AdminNotificationRepository;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test that the workflow enqueues exactly the
 * {@link NotificationMatrix} set for a lifecycle event (design §5.2,
 * §Correctness Properties (16)).
 *
 * Feature: role-based-order-workflow, Property 16: The enqueued notification set
 * equals the matrix.
 *
 * **Validates: Requirements 7.1, 7.2, 7.3, 8.4, 8.5, 9.7, 10.6, 10.7, 10.8, 11.3, 11.4, 11.5, 11.6, 11.7, 13.2, 13.3**
 *
 * <p>In-memory: the real {@link NotificationDispatcher} + publishers are wired
 * over <em>mocked repository interfaces</em> (Spring Data interfaces — permitted
 * on the Java 25 runtime; no concrete class is mocked) that record every enqueued
 * outbox row and in-app notification. For an order with all contact details and a
 * salesperson creator present, the reconstructed set of actually-enqueued
 * {@code (channel, recipient)} notifications must equal {@code matrix.specsFor(event)}.
 * Each {@code @Property} runs the jqwik default of 1000 tries (≥ 100).
 */
class NotificationMatrixEnqueuePropertyTest {

    private final NotificationMatrix matrix = new NotificationMatrix();

    // Feature: role-based-order-workflow, Property 16: The enqueued notification set equals the matrix
    // **Validates: Requirements 7.1, 7.2, 7.3, 8.4, 8.5, 9.7, 10.6, 10.7, 10.8, 11.3, 11.4, 11.5, 11.6, 11.7, 13.2, 13.3**
    @Property
    void enqueuedSetEqualsMatrix(@ForAll("statuses") OrderStatus event) {
        Recorder recorder = new Recorder();
        NotificationDispatcher dispatcher = recorder.dispatcher(matrix);

        // An order with all contacts present and a salesperson creator, so every
        // matrix spec is addressable and nothing is skipped.
        NotificationTarget target = new NotificationTarget(
                42L, "SHR-000042", "9812345678", "asha@example.com", "Asha",
                7L, PaymentStatus.COD, new BigDecimal("240.00"));

        NotificationDispatcher.Result result = dispatcher.dispatch(target, event, null);

        Set<NotificationSpec> expected = matrix.specsFor(event);

        // The dispatcher's own accounting: everything enqueued, nothing skipped.
        assertThat(result.enqueued()).isEqualTo(expected);
        assertThat(result.skipped()).isEmpty();

        // The actual side effects (recorded outbox rows + in-app rows) reconstruct
        // to exactly the matrix set — no more, no less.
        assertThat(recorder.reconstructEnqueuedSpecs()).isEqualTo(expected);
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    /** Records enqueued outbox rows and in-app notifications via mocked repos. */
    static final class Recorder {
        final List<OutboxEvent> outboxEvents = new ArrayList<>();
        final List<AdminNotification> adminNotifications = new ArrayList<>();

        NotificationDispatcher dispatcher(NotificationMatrix matrix) {
            OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
            when(outboxRepo.save(any(OutboxEvent.class))).thenAnswer(i -> {
                OutboxEvent e = i.getArgument(0);
                outboxEvents.add(e);
                return e;
            });
            OutboxEventPublisher outboxPublisher = new OutboxEventPublisher(outboxRepo);

            AdminNotificationRepository adminRepo = mock(AdminNotificationRepository.class);
            when(adminRepo.existsBySourceEventIdAndRecipientRoleAndRecipientUserId(any(), any(), any()))
                    .thenReturn(false);
            when(adminRepo.save(any(AdminNotification.class))).thenAnswer(i -> {
                AdminNotification n = i.getArgument(0);
                adminNotifications.add(n);
                return n;
            });

            WhatsAppNotificationPublisher whatsApp = new WhatsAppNotificationPublisher(
                    new WhatsAppMessageFactory(new WhatsAppTemplateRegistry()), outboxPublisher);
            MailNotificationPublisher mail = new MailNotificationPublisher(outboxPublisher);
            StaffNotificationDispatcher staff = new StaffNotificationDispatcher(adminRepo,
                    new com.shifa.oms.push.WebPushService(
                            null, new com.fasterxml.jackson.databind.ObjectMapper(), "", "", ""));
            return new NotificationDispatcher(matrix, whatsApp, mail, staff);
        }

        /** Reconstructs the (channel, recipient) set from the recorded side effects. */
        Set<NotificationSpec> reconstructEnqueuedSpecs() {
            Set<NotificationSpec> specs = new LinkedHashSet<>();
            for (OutboxEvent e : outboxEvents) {
                if (OutboxEvent.EVENT_WHATSAPP_NOTIFY.equals(e.getEventType())) {
                    specs.add(NotificationSpec.of(
                            NotificationChannel.WHATSAPP, NotificationRecipient.customer()));
                } else if (OutboxEvent.EVENT_EMAIL_NOTIFY.equals(e.getEventType())) {
                    specs.add(NotificationSpec.of(
                            NotificationChannel.EMAIL, NotificationRecipient.customer()));
                }
            }
            for (AdminNotification n : adminNotifications) {
                if (n.getRecipientRole() != null) {
                    specs.add(NotificationSpec.of(
                            NotificationChannel.IN_APP,
                            NotificationRecipient.role(n.getRecipientRole())));
                } else if (n.getRecipientUserId() != null) {
                    specs.add(NotificationSpec.of(
                            NotificationChannel.IN_APP,
                            NotificationRecipient.salespersonCreator()));
                }
            }
            return specs;
        }
    }
}
