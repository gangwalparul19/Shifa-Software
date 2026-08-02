package com.shifa.oms.integration.quikshipx;

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
import java.util.Objects;

/**
 * Scheduled drainer for {@code QUIKSHIPX_PUBLISH} outbox events (Req 5.1, 5.6, 5.7).
 *
 * <p>Mirrors {@code OutboxCourierDrainer}: picks up due {@code PENDING} rows and performs
 * the side-effecting call, with bounded retries and error tracking.
 *
 * <ul>
 *   <li><b>published</b> &rarr; {@code SENT};</li>
 *   <li><b>skipped</b> (wrong channel, already published, defaults incomplete, …) &rarr;
 *       {@code SENT} with the reason logged. A skip is a settled decision, not a
 *       transient failure, so retrying it would burn the ladder for nothing;</li>
 *   <li><b>retryable failure with attempts left</b> &rarr; stays {@code PENDING},
 *       {@code next_attempt_at} pushed out by the exponential ladder;</li>
 *   <li><b>permanent rejection, or attempts exhausted</b> &rarr; {@code FAILED}, the
 *       failure recorded in the integration event store, and an ADMIN notification
 *       raised. The order retains {@code APPROVED} throughout.</li>
 * </ul>
 *
 * <p>Each event is processed in its own transaction, so one poisoned order cannot stall
 * the queue.
 */
@Component
public class QuikShipXPublishDrainer {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXPublishDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final QuikShipXPublisher publisher;
    private final QuikShipXProperties properties;

    public QuikShipXPublishDrainer(OutboxEventRepository outboxEventRepository,
                                   OutboxEventPublisher outboxEventPublisher,
                                   QuikShipXPublisher publisher,
                                   QuikShipXProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.publisher = publisher;
        this.properties = properties;
    }

    /** Scheduled entry point: drains due publications every 15 seconds. */
    @Scheduled(fixedDelayString = "${app.quikshipx.publish-drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainPublications();
        } catch (RuntimeException e) {
            log.warn("QuikShipX publication drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due publication events once. Exposed for direct invocation
     * from tests.
     *
     * @return the count of events attempted
     */
    public int drainPublications() {
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_QUIKSHIPX_PUBLISH, OutboxEvent.STATUS_PENDING, LocalDateTime.now());
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        Long orderId = Objects.requireNonNull(event.getAggregateId(), "outbox aggregateId");
        String orderCode = orderCode(event, orderId);
        try {
            QuikShipXPublisher.Result result = publisher.publish(orderId);
            if (!result.published()) {
                log.info("QuikShipX publication for order {} skipped: {} ({})",
                        orderCode, result.skip(), result.detail());
            }
            event.markSent();
            outboxEventRepository.save(event);
        } catch (QuikShipXPublicationException ex) {
            handleFailure(event, orderId, orderCode, ex.getMessage(), ex.isRetryable());
        } catch (RuntimeException ex) {
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            // An unexpected fault is treated as retryable: it may be a transient
            // database or context problem rather than a bad order.
            handleFailure(event, orderId, orderCode, error, true);
        }
    }

    private void handleFailure(OutboxEvent event, Long orderId, String orderCode,
                              String error, boolean retryable) {
        int attemptsMade = event.getAttempts() + 1;
        boolean exhausted = !retryable
                || !RetryBackoff.hasAttemptsLeft(attemptsMade, properties.maxAttempts());

        if (exhausted) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            publisher.recordTerminalFailure(orderId, orderCode, error, attemptsMade);
            // Exactly one ADMIN notification naming the order (Req 5.7, 5.11).
            outboxEventPublisher.publishQuikShipXPublishFailed(orderId, orderCode, error);
            log.warn("QuikShipX publication for order {} failed permanently after {} attempt(s): {}",
                    orderCode, attemptsMade, error);
            return;
        }

        LocalDateTime next = LocalDateTime.now().plus(RetryBackoff.nextDelay(
                attemptsMade, properties.retryBackoff(), properties.retryMaxBackoff()));
        event.recordRetry(error, next);
        outboxEventRepository.save(event);
        log.info("QuikShipX publication for order {} will retry at {} (attempt {} of {}): {}",
                orderCode, next, attemptsMade, properties.maxAttempts(), error);
    }

    private String orderCode(OutboxEvent event, Long orderId) {
        if (event.getPayload() == null) {
            return String.valueOf(orderId);
        }
        return String.valueOf(event.getPayload().getOrDefault("orderCode", orderId));
    }
}
