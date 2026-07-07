package com.shifa.oms.notification;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-style test (mock {@link WhatsAppClient}) for the WhatsApp
 * notification path (Req 14.1, 14.4). Wires the real
 * {@link WhatsAppNotificationPublisher} and {@link WhatsAppOutboxDrainer} against
 * mocked repositories and the {@link MockWhatsAppClient}, so no database or live
 * Meta API is required.
 *
 * <ul>
 *   <li>a template send on Dispatched succeeds and is recorded by the client;</li>
 *   <li>a simulated send failure, after retries are exhausted, records the
 *       failure and flags the order via a {@code WHATSAPP_FAILED} notification
 *       (Req 14.4).</li>
 * </ul>
 */
class WhatsAppNotificationIntegrationTest {

    private OutboxEventRepository outboxRepository;
    private List<OutboxEvent> savedEvents;
    private OutboxEventPublisher publisher;
    private WhatsAppNotificationPublisher notificationPublisher;
    private MockWhatsAppClient client;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxEventRepository.class);
        savedEvents = new ArrayList<>();
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(i -> {
            OutboxEvent e = i.getArgument(0);
            if (!savedEvents.contains(e)) {
                savedEvents.add(e);
            }
            return e;
        });
        publisher = new OutboxEventPublisher(outboxRepository);
        notificationPublisher = new WhatsAppNotificationPublisher(
                new WhatsAppMessageFactory(new WhatsAppTemplateRegistry()), publisher);
        client = new MockWhatsAppClient();
    }

    @Test
    void dispatchTemplateSendSucceedsAndIsRecorded() {
        // Enqueue a dispatch notification for a COD order.
        NotificationContext context = new NotificationContext(
                "SHR-ABC123", "9812345678", "Shifa Express", "AWB0001234567",
                "https://track.example.com/AWB0001234567", LocalDate.now().plusDays(4),
                PaymentStatus.COD, new BigDecimal("240.00"));
        WhatsAppMessage enqueued = notificationPublisher.enqueue(50L, NotificationEvent.DISPATCHED, context);
        assertThat(enqueued).isNotNull();
        OutboxEvent notifyEvent = savedEvents.get(0);
        assertThat(notifyEvent.getEventType()).isEqualTo(OutboxEvent.EVENT_WHATSAPP_NOTIFY);

        WhatsAppOutboxDrainer drainer = drainer(3);
        when(outboxRepository.findDue(any(), any(), any())).thenReturn(List.of(notifyEvent));

        int processed = drainer.drainNotifications();

        assertThat(processed).isEqualTo(1);
        // The event is marked SENT.
        assertThat(notifyEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
        // The mock client recorded exactly the dispatched template + params.
        assertThat(client.sentMessages()).hasSize(1);
        WhatsAppMessage sent = client.sentMessages().get(0);
        assertThat(sent.recipientMobile()).isEqualTo("9812345678");
        assertThat(sent.templateName()).isEqualTo("order_dispatched");
        assertThat(sent.parameterValue(WhatsAppTemplateRegistry.PARAM_AWB)).contains("AWB0001234567");
        assertThat(sent.parameterValue(WhatsAppTemplateRegistry.PARAM_COD_AMOUNT)).contains("240.00");
        // No admin failure notification was produced.
        assertThat(savedEvents).noneMatch(e ->
                OutboxEvent.EVENT_WHATSAPP_FAILED.equals(e.getEventType()));
    }

    @Test
    void simulatedSendFailureRecordsFailureAndFlagsOrder() {
        NotificationContext context = new NotificationContext(
                "SHR-XYZ999", "9812345678", "Shifa Express", "AWB0009999999",
                "https://track.example.com/AWB0009999999", LocalDate.now().plusDays(4),
                PaymentStatus.FULLY_PAID, BigDecimal.ZERO.setScale(2));
        notificationPublisher.enqueue(60L, NotificationEvent.DISPATCHED, context);
        OutboxEvent notifyEvent = savedEvents.get(0);

        // Force the mock client to fail every send; maxAttempts = 1 so it exhausts at once.
        client.simulateFailure(true);
        WhatsAppOutboxDrainer drainer = drainer(1);
        when(outboxRepository.findDue(any(), any(), any())).thenReturn(List.of(notifyEvent));

        drainer.drainNotifications();

        // Nothing was actually sent.
        assertThat(client.sentMessages()).isEmpty();
        // The notify event is marked FAILED with the error recorded (Req 14.4).
        assertThat(notifyEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        assertThat(notifyEvent.getLastError()).isNotBlank();
        // An admin WHATSAPP_FAILED notification flags the order for review (Req 14.4).
        OutboxEvent failed = savedEvents.stream()
                .filter(e -> OutboxEvent.EVENT_WHATSAPP_FAILED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a WHATSAPP_FAILED notification"));
        assertThat(failed.getAggregateId()).isEqualTo(60L);
        assertThat(failed.getPayload()).containsKey("error");
        assertThat(failed.getPayload()).containsEntry("templateName", "order_dispatched");
    }

    @Test
    void failTokenInOrderIdTriggersFailureWithoutForcedMode() {
        // A FAIL token in the order code makes the mock client fail (demo path).
        NotificationContext context = new NotificationContext(
                "SHR-FAIL01", "9812345678", "Shifa Express", "AWB0000000001",
                "https://track.example.com/AWB0000000001", LocalDate.now().plusDays(4),
                PaymentStatus.COD, new BigDecimal("100.00"));
        notificationPublisher.enqueue(70L, NotificationEvent.DISPATCHED, context);
        OutboxEvent notifyEvent = savedEvents.get(0);

        WhatsAppOutboxDrainer drainer = drainer(1);
        when(outboxRepository.findDue(any(), any(), any())).thenReturn(List.of(notifyEvent));

        drainer.drainNotifications();

        assertThat(client.sentMessages()).isEmpty();
        assertThat(notifyEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        assertThat(savedEvents).anyMatch(e ->
                OutboxEvent.EVENT_WHATSAPP_FAILED.equals(e.getEventType()));
    }

    private WhatsAppOutboxDrainer drainer(int maxAttempts) {
        WhatsAppProperties properties = new WhatsAppProperties(
                "MOCK", null, null, null, maxAttempts, Duration.ofSeconds(30));
        return new WhatsAppOutboxDrainer(outboxRepository, publisher, client, properties);
    }
}
