package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.RetryBackoff;
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
 * Scheduled drainer for {@code SHOPIFY_ORDER_INGEST} outbox events (Req 2.5, 2.9, 2.10).
 *
 * <p>Mirrors {@link com.shifa.oms.integration.quikshipx.QuikShipXPublishDrainer} and the
 * pre-existing {@code OutboxCourierDrainer}, so the retry semantics of every integration in
 * this system read the same way:
 *
 * <ul>
 *   <li><b>ingested</b> (or settled: disabled, malformed, already processed) &rarr;
 *       {@code SENT}. A settled decision is not a transient failure, so retrying it would
 *       only delay the admin alert;</li>
 *   <li><b>retryable failure with attempts left</b> &rarr; stays {@code PENDING} with
 *       {@code next_attempt_at} pushed out by the 30s-doubling ladder (Req 2.9);</li>
 *   <li><b>attempts exhausted</b> &rarr; {@code FAILED}, {@code PROCESSING_FAILED} already
 *       recorded on the integration event by the ingestor, and one ADMIN notification
 *       raised (Req 2.10). Every order is left exactly as it was.</li>
 * </ul>
 *
 * <p>The default budget is 1 attempt plus 3 retries, which is where
 * {@code app.shopify.max-attempts} defaults to 4.
 */
@Component
public class ShopifyIngestDrainer {

    private static final Logger log = LoggerFactory.getLogger(ShopifyIngestDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ShopifyOrderIngestor ingestor;
    private final ShopifyProperties properties;

    public ShopifyIngestDrainer(OutboxEventRepository outboxEventRepository,
                                OutboxEventPublisher outboxEventPublisher,
                                ShopifyOrderIngestor ingestor,
                                ShopifyProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.ingestor = ingestor;
        this.properties = properties;
    }

    /**
     * Scheduled entry point. The 15 second default keeps the "processing begins within
     * 60 seconds of receipt" guarantee (Req 2.5, 3.1) with room to spare.
     */
    @Scheduled(fixedDelayString = "${app.shopify.ingest-drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainIngestions();
        } catch (RuntimeException e) {
            log.warn("Shopify ingest drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due ingestion events once. Exposed for direct invocation from
     * tests.
     *
     * @return the count of events attempted
     */
    public int drainIngestions() {
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_SHOPIFY_ORDER_INGEST, OutboxEvent.STATUS_PENDING, LocalDateTime.now());
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        Long integrationEventId = event.getAggregateId();
        String shopifyOrderId = payloadValue(event, "shopifyOrderId");
        try {
            ShopifyOrderIngestor.Result result = ingestor.ingest(integrationEventId);
            if (result.retryable()) {
                handleFailure(event, integrationEventId, shopifyOrderId, result.detail());
                return;
            }
            if (!result.isSuccess()) {
                log.info("Shopify ingestion of event {} settled without an order: {}",
                        integrationEventId, result.detail());
            }
            event.markSent();
            outboxEventRepository.save(event);
        } catch (RuntimeException ex) {
            // An unexpected fault is treated as retryable: it may be a transient database
            // or context problem rather than a bad payload.
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            handleFailure(event, integrationEventId, shopifyOrderId, error);
        }
    }

    private void handleFailure(OutboxEvent event, Long integrationEventId,
                               String shopifyOrderId, String error) {
        int attemptsMade = event.getAttempts() + 1;
        if (!RetryBackoff.hasAttemptsLeft(attemptsMade, properties.maxAttempts())) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            // The PROCESSING_FAILED outcome is already on the integration event; this is
            // the single ADMIN notification naming the Shopify order (Req 2.10).
            outboxEventPublisher.publishShopifyIngestFailed(integrationEventId, shopifyOrderId, error);
            log.warn("Shopify ingestion of order {} (event {}) failed permanently after {} attempt(s): {}",
                    shopifyOrderId, integrationEventId, attemptsMade, error);
            return;
        }

        LocalDateTime next = LocalDateTime.now().plus(RetryBackoff.nextDelay(
                attemptsMade, properties.retryBackoff(), properties.retryMaxBackoff()));
        event.recordRetry(error, next);
        outboxEventRepository.save(event);
        log.info("Shopify ingestion of order {} will retry at {} (attempt {} of {}): {}",
                shopifyOrderId, next, attemptsMade, properties.maxAttempts(), error);
    }

    private static String payloadValue(OutboxEvent event, String key) {
        if (event.getPayload() == null) {
            return null;
        }
        Object value = event.getPayload().get(key);
        return value == null ? null : String.valueOf(value);
    }
}
