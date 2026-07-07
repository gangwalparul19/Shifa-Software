package com.shifa.oms.courier;

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
 * Scheduled drainer for {@code COURIER_ASSIGN} outbox events (Req 12.1, 12.4).
 *
 * <p>Picks up {@code PENDING} courier-assign rows that are due (never attempted,
 * or past their backoff window) and performs the side-effecting courier call via
 * {@link CourierAssignmentService}, with bounded retries and error tracking:
 * <ul>
 *   <li><b>success</b> &rarr; the event is marked {@code SENT};</li>
 *   <li><b>failure with retries left</b> &rarr; the event stays {@code PENDING},
 *       {@code attempts} is incremented, {@code last_error} is recorded, and
 *       {@code next_attempt_at} is pushed out by the configured backoff;</li>
 *   <li><b>failure with retries exhausted</b> &rarr; the event is marked
 *       {@code FAILED} and a {@code COURIER_ASSIGN_FAILED} admin notification is
 *       produced (Req 12.4).</li>
 * </ul>
 * Because {@link CourierAssignmentService#assignForOrder(Long)} rolls back on a
 * courier error, the order always retains {@code Packed} on failure.
 *
 * <p>Each event's outcome is persisted independently, so one poisoned event does
 * not stall the rest of the queue.
 */
@Component
public class OutboxCourierDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxCourierDrainer.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final CourierAssignmentService assignmentService;
    private final CourierProperties properties;

    public OutboxCourierDrainer(OutboxEventRepository outboxEventRepository,
                                OutboxEventPublisher outboxEventPublisher,
                                CourierAssignmentService assignmentService,
                                CourierProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.assignmentService = assignmentService;
        this.properties = properties;
    }

    /** Scheduled entry point: drains due courier-assign events every 15 seconds. */
    @Scheduled(fixedDelayString = "${app.courier.drain-interval-ms:15000}")
    public void scheduledDrain() {
        try {
            drainCourierAssignments();
        } catch (RuntimeException e) {
            log.warn("Courier assignment drain cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Drains all currently-due courier-assign events once. Returns the number of
     * events processed (attempted) this cycle. Exposed for direct invocation from
     * tests.
     *
     * @return the count of events attempted
     */
    public int drainCourierAssignments() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> due = outboxEventRepository.findDue(
                OutboxEvent.EVENT_COURIER_ASSIGN, OutboxEvent.STATUS_PENDING, now);
        int processed = 0;
        for (OutboxEvent event : due) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    private void processOne(OutboxEvent event) {
        Long orderId = aggregateId(event);
        try {
            assignmentService.assignForOrder(orderId);
            event.markSent();
            outboxEventRepository.save(event);
        } catch (RuntimeException ex) {
            handleFailure(event, orderId, ex);
        }
    }

    private void handleFailure(OutboxEvent event, Long orderId, RuntimeException ex) {
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        boolean exhausted = event.getAttempts() + 1 >= properties.maxAttempts();
        if (exhausted) {
            event.markFailed(error);
            outboxEventRepository.save(event);
            // Persist the admin-facing failure notification; order retains Packed (Req 12.4).
            String orderCode = String.valueOf(event.getPayload() == null
                    ? orderId : event.getPayload().getOrDefault("orderCode", orderId));
            outboxEventPublisher.publishCourierAssignFailed(orderId, orderCode, error);
            log.warn("Courier assignment for order {} failed after {} attempts: {}",
                    orderId, event.getAttempts(), error);
        } else {
            LocalDateTime next = LocalDateTime.now().plus(properties.retryBackoff());
            event.recordRetry(error, next);
            outboxEventRepository.save(event);
            log.debug("Courier assignment for order {} will retry at {} ({})",
                    orderId, next, error);
        }
    }

    private Long aggregateId(OutboxEvent event) {
        return Objects.requireNonNull(event.getAggregateId(), "outbox aggregateId");
    }
}
