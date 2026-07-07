package com.shifa.oms.notification;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Scheduled drainer for {@code WHATSAPP_NOTIFY} outbox events (Req 14.1, 14.2,
 * 14.4), mirroring the courier drainer.
 *
 * <p>Picks up {@code PENDING} WhatsApp-notify rows that are due (never attempted,
 * or past their backoff window), reconstructs the resolved {@link WhatsAppMessage}
 * from the payload, and sends it via the {@link WhatsAppClient} with bounded
 * retries and error tracking:
 * <ul>
 *   <li><b>success</b> &rarr; the event is marked {@code SENT};</li>
 *   <li><b>failure with retries left</b> &rarr; the event stays {@code PENDING},
 *       {@code attempts} is incremented, {@code last_error} is recorded, and
 *       {@code next_attempt_at} is pushed out by the configured backoff;</li>
 *   <li><b>failure with retries exhausted</b> &rarr; the event is marked
 *       {@code FAILED} and a {@code WHATSAPP_FAILED} admin notification is
 *       produced, flagging the order for review (Req 14.4).</li>
 * </ul>
 * Each event's outcome is persisted independently, so one poisoned message does
 * not stall the rest of the queue.
 */
@Component
public class WhatsAppOutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOutboxDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final WhatsAppClient whatsAppClient;
    private final WhatsAppProperties properties;

    public WhatsAppOutboxDrainer(OutboxEventRepository outboxEventRepository,
                                 OutboxEventPublisher outboxEventPublisher,
                                 WhatsAppClient whatsAppClient,
                                 WhatsAppProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.whatsAppClient = whatsAppClient;
        this.properties = properties;
    }

    /** Scheduled entry point: drains due WhatsApp-notify events every 15 seconds. */
    @Scheduled(fixedDelayString = "${app.whatsapp.drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainNotifications();
        } catch (RuntimeException e) {
            log.warn("WhatsApp notification drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due WhatsApp-notify events once.
     *
     * @return the number of events attempted this cycle
     */
    public int drainNotifications() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_WHATSAPP_NOTIFY, OutboxEvent.STATUS_PENDING, now);
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        try {
            WhatsAppMessage message = WhatsAppNotificationPublisher.messageFromPayload(payload(event));
            whatsAppClient.send(message);
            event.markSent();
            outboxEventRepository.save(event);
            log.debug("WhatsApp notification for order {} sent (template {})",
                    event.getAggregateId(), message.templateName());
        } catch (RuntimeException ex) {
            handleFailure(event, ex);
        }
    }

    private void handleFailure(OutboxEvent event, RuntimeException ex) {
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        boolean exhausted = event.getAttempts() + 1 >= properties.maxAttempts();
        if (exhausted) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            // Record the admin-facing failure and flag the order for review (Req 14.4).
            Map<String, Object> payload = payload(event);
            String orderCode = asString(payload.getOrDefault("orderCode", event.getAggregateId()));
            String templateName = asString(payload.get("templateName"));
            outboxEventPublisher.publishWhatsAppFailed(
                    event.getAggregateId(), orderCode, templateName, error);
            log.warn("WhatsApp notification for order {} failed after {} attempts: {}",
                    event.getAggregateId(), event.getAttempts(), error);
        } else {
            LocalDateTime next = LocalDateTime.now().plus(properties.retryBackoff());
            event.recordRetry(error, next);
            outboxEventRepository.save(event);
            log.debug("WhatsApp notification for order {} will retry at {} ({})",
                    event.getAggregateId(), next, error);
        }
    }

    private Map<String, Object> payload(OutboxEvent event) {
        Map<String, Object> payload = event.getPayload();
        return payload == null ? Map.of() : payload;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
