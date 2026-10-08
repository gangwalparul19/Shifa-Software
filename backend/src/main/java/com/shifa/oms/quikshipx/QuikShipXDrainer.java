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
    /**
     * Optional admin-notification service so a PERMANENTLY-failed QuikShipX event
     * (create/confirm/allot exhausted its retries) raises an admin alert instead
     * of silently stranding the order — the packing/admin team then knows a Shopify
     * order did not get its tracking id and can recover it. Nullable in tests.
     */
    private final com.shifa.oms.adminnotification.AdminNotificationService adminNotificationService;

    /** Test constructor without the admin-alert collaborator (no alert on permanent failure). */
    public QuikShipXDrainer(OutboxEventRepository outboxEventRepository,
                            QuikShipXService quikShipXService,
                            QuikShipXProperties properties) {
        this(outboxEventRepository, quikShipXService, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QuikShipXDrainer(OutboxEventRepository outboxEventRepository,
                            QuikShipXService quikShipXService,
                            QuikShipXProperties properties,
                            com.shifa.oms.adminnotification.AdminNotificationService adminNotificationService) {
        this.outboxEventRepository = outboxEventRepository;
        this.quikShipXService = quikShipXService;
        this.properties = properties;
        this.adminNotificationService = adminNotificationService;
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
     * Self-healing re-drive: periodically re-queues permanently-FAILED QuikShipX
     * events whose failure looks transient (e.g. the courier API returned HTTP 500
     * / timed out / "not ready" during a brief outage) back to {@code PENDING} so
     * the normal drainer retries them once QuikShipX recovers. Without this, a
     * transient outage that outlasts the retry ladder would strand an order at
     * "Confirmed" with no tracking id forever (observed in production). All
     * QuikShipX operations are idempotent, so re-driving an already-completed one
     * is safe. Genuinely permanent failures (bad address/HSN) do not match the
     * transient-error allow-list and are left FAILED for manual review.
     */
    @Scheduled(fixedDelayString = "${app.quikshipx.redrive-interval-ms:600000}")
    public void scheduledRedrive() {
        try {
            redriveFailedTransient();
        } catch (RuntimeException e) {
            log.warn("QuikShipX re-drive cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Resets transient-looking FAILED QuikShipX events to PENDING (attempts 0,
     * due now). Returns the number re-queued. Exposed for direct invocation from
     * tests and the admin recover action.
     */
    public int redriveFailedTransient() {
        int requeued = 0;
        for (OutboxEvent event : outboxEventRepository.findByStatusAndEventTypePrefix(
                OutboxEvent.STATUS_FAILED, "QUIKSHIPX")) {
            if (!looksTransient(event.getLastError())) {
                continue; // a genuinely permanent failure — leave for manual review
            }
            // Back to PENDING with a fresh retry ladder (attempts 0, due now), so the
            // full tolerance window is available again for this recovery attempt.
            event.requeue();
            outboxEventRepository.save(event);
            requeued++;
        }
        if (requeued > 0) {
            log.info("QuikShipX self-heal: re-queued {} transiently-failed event(s) to PENDING", requeued);
        }
        return requeued;
    }

    /** Whether a FAILED event's last error looks like a transient/retryable cause worth re-driving. */
    private static boolean looksTransient(String lastError) {
        if (lastError == null) {
            return true; // unknown cause — safe to retry (operations are idempotent)
        }
        String e = lastError.toLowerCase(java.util.Locale.ROOT);
        return e.contains("http 500") || e.contains("http 502") || e.contains("http 503")
                || e.contains("http 504") || e.contains("http 408") || e.contains("http 429")
                || e.contains("timed out") || e.contains("timeout") || e.contains("failed to connect")
                || e.contains("not ready") || e.contains("not created yet") || e.contains("will retry");
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
            // Surface the reason on the order so the admin sees it on the drawer and
            // can re-route to in-house (courier-failure re-route feature). Best-effort.
            recordFailureReasonSafely(orderId, error);
            alertPermanentFailure(event, orderId, error);
        } else {
            LocalDateTime next = LocalDateTime.now().plus(properties.retryBackoff());
            event.recordRetry(error, next);
            outboxEventRepository.save(event);
            log.debug("QuikShipX {} for order {} will retry at {} ({})",
                    event.getEventType(), orderId, next, error);
        }
    }

    /**
     * Records the permanent-failure reason on the order (its own transaction via
     * the service). Best-effort — a failure to persist the reason never disturbs
     * the drainer (the outbox row is already marked FAILED + the admin is alerted).
     */
    private void recordFailureReasonSafely(Long orderId, String error) {
        try {
            quikShipXService.recordFailureReason(orderId, error);
        } catch (RuntimeException ex) {
            log.warn("Failed to record QuikShipX failure reason on order {}: {}", orderId, ex.getMessage());
        }
    }

    /**
     * Raises an admin alert when a QuikShipX event fails permanently, so an order
     * that could not get its shipment/tracking id is surfaced rather than silently
     * stranded. Best-effort: never lets an alerting failure disturb the drainer.
     */
    private void alertPermanentFailure(OutboxEvent event, Long orderId, String error) {
        if (adminNotificationService == null) {
            return;
        }
        try {
            String step = switch (event.getEventType()) {
                case OutboxEvent.EVENT_QUIKSHIPX_CREATE -> "create shipment";
                case OutboxEvent.EVENT_QUIKSHIPX_CONFIRM -> "confirm shipment";
                case OutboxEvent.EVENT_QUIKSHIPX_ALLOT -> "allot tracking id";
                default -> event.getEventType();
            };
            adminNotificationService.record(
                    "QUIKSHIPX_FAILED",
                    "QuikShipX " + step + " failed for order " + orderId,
                    "The QuikShipX step '" + step + "' could not complete after retries: " + error
                            + ". The order did not get its tracking id — recover it from the Shopify "
                            + "recover action or investigate QuikShipX.",
                    com.shifa.oms.adminnotification.AdminNotification.SEVERITY_DANGER,
                    orderId, null, event.getId());
        } catch (RuntimeException alertEx) {
            log.warn("Failed to raise admin alert for QuikShipX failure on order {}: {}",
                    orderId, alertEx.getMessage());
        }
    }
}
