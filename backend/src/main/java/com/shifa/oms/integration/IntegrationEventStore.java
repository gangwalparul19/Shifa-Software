package com.shifa.oms.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Write side of the integration event store — the durable record of every inbound
 * delivery and every outbound publication attempt (V49 {@code integration_events}).
 *
 * <p>Two deliberate choices:
 *
 * <ul>
 *   <li><b>{@link Propagation#REQUIRES_NEW}</b> on the write paths. A rollback in
 *       the enclosing request or drainer transaction must not erase the evidence
 *       that a delivery arrived, otherwise a failure becomes invisible and the
 *       admin console silently under-reports.</li>
 *   <li><b>Duplicate detection by unique-constraint violation</b> rather than a
 *       read-then-write check. Providers retry concurrently, so a check-first
 *       approach races; letting the database arbitrate makes
 *       {@link #record} idempotent under concurrency (Req 2.6, 2.7, 6.11).</li>
 * </ul>
 */
@Service
public class IntegrationEventStore {

    private final IntegrationEventRepository repository;
    private final Clock clock;

    @Autowired
    public IntegrationEventStore(IntegrationEventRepository repository) {
        // Single @Autowired constructor: a service with a Clock test constructor and
        // no @Autowired on the primary one fails the whole context at startup.
        this(repository, Clock.system(ZoneId.of("Asia/Kolkata")));
    }

    /** Test seam: fixes the clock so recorded timestamps are deterministic. */
    public IntegrationEventStore(IntegrationEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records a received delivery.
     *
     * @return the stored event, or {@link Optional#empty()} when an event with this
     *         {@code (source, externalEventId)} already exists — the caller should
     *         then acknowledge the delivery and do nothing else (Req 2.6, 6.11).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IntegrationEvent> record(IntegrationSource source, String externalEventId,
                                             String eventTopic, String rawPayload) {
        if (repository.existsBySourceAndExternalEventId(source, externalEventId)) {
            return Optional.empty();
        }
        IntegrationEvent event = new IntegrationEvent(
                source, externalEventId, eventTopic, rawPayload, LocalDateTime.now(clock));
        try {
            return Optional.of(repository.saveAndFlush(event));
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race against a concurrent delivery of the same event. The
            // other writer stored it, so this delivery is a no-op.
            return Optional.empty();
        }
    }

    /**
     * Records a terminal outcome against a stored event, stamping the attempt count.
     * Runs in its own transaction so a failure verdict survives the rollback of the
     * work that failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOutcome(Long eventId, IntegrationOutcome outcome, String failureReason) {
        repository.findById(eventId).ifPresent(event -> {
            event.recordOutcome(outcome, failureReason, LocalDateTime.now(clock));
            repository.save(event);
        });
    }

    /** Records a terminal outcome together with the order the event resolved to. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOutcome(Long eventId, IntegrationOutcome outcome, String failureReason, Long orderId) {
        repository.findById(eventId).ifPresent(event -> {
            event.setOrderId(orderId);
            event.recordOutcome(outcome, failureReason, LocalDateTime.now(clock));
            repository.save(event);
        });
    }

    /**
     * Records an outbound publication failure for an order that has no inbound
     * delivery to attach to. Keyed on the order code so repeated failures for the
     * same order update one row rather than accumulating noise in the console.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPublicationFailure(String orderCode, Long orderId, IntegrationOutcome outcome,
                                         String failureReason, int attemptCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        IntegrationEvent event = repository
                .findBySourceAndExternalEventId(IntegrationSource.QUIKSHIPX, orderCode)
                .orElseGet(() -> new IntegrationEvent(
                        IntegrationSource.QUIKSHIPX, orderCode, "publication", null, now));
        event.setOrderId(orderId);
        event.setAttemptCount(attemptCount);
        event.recordOutcome(outcome, failureReason, now);
        repository.save(event);
    }

    /** Notes that an admin replay produced the current outcome (Req 14.4). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReplayed(Long eventId, String replayedBy) {
        repository.findById(eventId).ifPresent(event -> {
            event.recordReplay(replayedBy, LocalDateTime.now(clock));
            repository.save(event);
        });
    }

    @Transactional(readOnly = true)
    public Optional<IntegrationEvent> find(Long eventId) {
        return repository.findById(eventId);
    }
}
