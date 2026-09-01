package com.shifa.oms.quikshipx;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scheduled drainer for the QuikShipX out-of-band events — {@code QUIKSHIPX_CREATE}
 * (create the shipment on punch) and {@code QUIKSHIPX_CONFIRM} (mirror Confirmed on
 * approval). Mirrors {@code OutboxCourierDrainer}: due {@code PENDING} rows are
 * processed with bounded retries and per-event error tracking, so a slow or
 * unavailable QuikShipX never blocks order entry/approval and one poisoned event
 * never stalls the queue.
 *
 * <p>Retry classification uses {@link QuikShipXException#isRetryable()}: a
 * non-retryable failure (e.g. a rejected address) is marked {@code FAILED} at
 * once rather than burning the retry ladder.
 */
@Component
public class QuikShipXDrainer {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final QuikShipXService quikShipXService;
    private final QuikShipXProperties properties;

    public QuikShipXDrainer(OutboxEventRepository outboxEventRepository,
                            QuikShipXService quikShipXService,
                            QuikShipXProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.quikShipXService = quikShipXService;
        this.properties = properties;
    }

    /** Scheduled entry point: drains due QuikShipX events every 15 seconds. */
    @Scheduled(fixedDelayString = "${app.quikshipx.drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            log.warn("QuikShipX drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due create + confirm events once. Returns the number of
     * events processed. Exposed for direct invocation from tests.
     */
    public int drainOnce() {
        int processed = 0;
        processed += drain(OutboxEvent.EVENT_QUIKSHIPX_CREATE, quikShipXService::createForOrder);
        processed += drain(OutboxEvent.EVENT_QUIKSHIPX_CONFIRM, quikShipXService::confirmForOrder);
        processed += drain(OutboxEvent.EVENT_QUIKSHIPX_ALLOT, quikShipXService::allotForOrder);
        return processed;
    }

    private int drain(String eventType, Consumer<Long> action) {
        List<OutboxEvent> due = outboxEventRepository.findDue(
                eventType, OutboxEvent.STATUS_PENDING, LocalDateTime.now());
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event, action);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event, Consumer<Long> action) {
        Long orderId = Objects.requireNonNull(event.getAggregateId(), "outbox aggregateId");
        try {
            action.accept(orderId);
            event.markSent();
            outboxEventRepository.save(event);
        } catch (RuntimeException ex) {
            handleFailure(event, orderId, ex);
        }
    }

    private void handleFailure(OutboxEvent event, Long orderId, RuntimeException ex) {
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        boolean retryable = !(ex instanceof QuikShipXException q) || q.isRetryable();
        boolean exhausted = event.getAttempts() + 1 >= properties.maxAttempts();
        if (!retryable || exhausted) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            log.warn("QuikShipX {} for order {} failed permanently after {} attempt(s): {}",
                    event.getEventType(), orderId, event.getAttempts() + 1, error);
        } else {
            LocalDateTime next = LocalDateTime.now().plus(properties.retryBackoff());
            event.recordRetry(error, next);
            outboxEventRepository.save(event);
            log.debug("QuikShipX {} for order {} will retry at {} ({})",
                    event.getEventType(), orderId, next, error);
        }
    }
}
