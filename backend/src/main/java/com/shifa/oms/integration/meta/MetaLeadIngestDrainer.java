package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.RetryBackoff;
import com.shifa.oms.integration.meta.MetaLeadIngestService.IngestResult;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled drainer for {@code META_LEAD_INGEST} outbox events (spec
 * {@code meta-lead-sync}, Req 8), mirroring {@link com.shifa.oms.integration.shopify.ShopifyIngestDrainer}
 * so every integration's retry semantics read the same way:
 *
 * <ul>
 *   <li><b>captured</b> or <b>settled</b> (duplicate / already processed / disabled)
 *       &rarr; {@code SENT};</li>
 *   <li><b>retryable failure with attempts left</b> &rarr; stays {@code PENDING} with
 *       {@code next_attempt_at} pushed out by the 30s-doubling ladder (Req 8.2);</li>
 *   <li><b>attempts exhausted</b>, or a <b>permanent failure</b> (bad token / capture
 *       rejection) &rarr; {@code FAILED} with one {@code META_LEAD_INGEST_FAILED}
 *       ADMIN notification (Req 5.4, 7.5, 8.3).</li>
 * </ul>
 *
 * <p>Each event's outcome is persisted independently, so one poisoned lead does not
 * stall the queue. The default budget is 1 attempt + 3 retries
 * ({@code app.meta.max-attempts=4}).
 */
@Component
public class MetaLeadIngestDrainer {

    private static final Logger log = LoggerFactory.getLogger(MetaLeadIngestDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final MetaLeadIngestService ingestService;
    private final MetaProperties properties;

    public MetaLeadIngestDrainer(OutboxEventRepository outboxEventRepository,
                                 OutboxEventPublisher outboxEventPublisher,
                                 MetaLeadIngestService ingestService,
                                 MetaProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.ingestService = ingestService;
        this.properties = properties;
    }

    /** Scheduled entry point: drains due Meta lead-ingest events. */
    @Scheduled(fixedDelayString = "${app.meta.ingest-drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainIngestions();
        } catch (RuntimeException e) {
            log.warn("Meta lead ingest drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due ingestion events once. Exposed for direct invocation
     * from tests.
     *
     * @return the count of events attempted
     */
    public int drainIngestions() {
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_META_LEAD_INGEST, OutboxEvent.STATUS_PENDING, LocalDateTime.now());
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        Long integrationEventId = event.getAggregateId();
        String leadgenId = payloadValue(event, "leadgenId");
        try {
            IngestResult result = ingestService.ingest(integrationEventId);
            if (result.isRetryable()) {
                handleRetryable(event, integrationEventId, leadgenId, result.detail());
                return;
            }
            if (result.isPermanentFailure()) {
                failPermanently(event, integrationEventId, leadgenId, result.detail());
                return;
            }
            // CAPTURED or SKIPPED — settled.
            event.markSent();
            outboxEventRepository.save(event);
        } catch (RuntimeException ex) {
            // An unexpected fault is treated as retryable.
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            handleRetryable(event, integrationEventId, leadgenId, error);
        }
    }

    private void handleRetryable(OutboxEvent event, Long integrationEventId,
                                 String leadgenId, String error) {
        int attemptsMade = event.getAttempts() + 1;
        if (!RetryBackoff.hasAttemptsLeft(attemptsMade, properties.maxAttempts())) {
            failPermanently(event, integrationEventId, leadgenId,
                    "retries exhausted after " + attemptsMade + " attempt(s): " + error);
            return;
        }
        LocalDateTime next = LocalDateTime.now().plus(RetryBackoff.nextDelay(
                attemptsMade, properties.retryBackoff(), properties.retryMaxBackoff()));
        event.recordRetry(error, next);
        outboxEventRepository.save(event);
        log.info("Meta lead {} will retry at {} (attempt {} of {}): {}",
                leadgenId, next, attemptsMade, properties.maxAttempts(), error);
    }

    private void failPermanently(OutboxEvent event, Long integrationEventId,
                                 String leadgenId, String error) {
        event.markFailed(error);
        outboxEventRepository.save(event);
        outboxEventPublisher.publishMetaLeadIngestFailed(integrationEventId, leadgenId, error);
        log.warn("Meta lead {} (event {}) failed permanently: {}", leadgenId, integrationEventId, error);
    }

    private static String payloadValue(OutboxEvent event, String key) {
        if (event.getPayload() == null) {
            return null;
        }
        Object value = event.getPayload().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
