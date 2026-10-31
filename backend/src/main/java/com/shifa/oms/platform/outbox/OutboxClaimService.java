package com.shifa.oms.platform.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claims pending integration events with a short database transaction, then
 * fences every completion with the claim token. The external side effect runs
 * after the claim transaction commits, so database locks are never held across
 * network calls. An expired lease is eligible for recovery after a worker crash.
 *
 * <p>This class is deliberately not used for the SSE relay: SSE connections are
 * process-local, so a global single-consumer lease would deliver an event to one
 * node and make admins connected to another node miss it. SSE keeps its
 * node-local overlap guard and remains a single-instance deployment concern.
 */
@Service
public class OutboxClaimService {

    private static final int MAX_CLAIM_BATCH_SIZE = 500;
    private final EntityManager entityManager;
    private final OutboxEventRepository repository;
    private final String runtimeOwner = "jvm-" + UUID.randomUUID();

    @Value("${app.outbox.claim.batch-size:50}")
    private int configuredBatchSize;

    @Value("${app.outbox.claim.lease-duration-ms:120000}")
    private long configuredLeaseDurationMs;

    @Value("${app.outbox.claim.owner:}")
    private String configuredOwner;

    public OutboxClaimService(EntityManager entityManager, OutboxEventRepository repository) {
        this.entityManager = entityManager;
        this.repository = repository;
    }

    /**
     * Claims at most the configured batch size. MySQL 8's {@code SKIP LOCKED}
     * lets concurrent instances take disjoint rows without waiting on each other.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimDue(String eventType, LocalDateTime now) {
        int limit = Math.min(MAX_CLAIM_BATCH_SIZE, Math.max(1, configuredBatchSize));
        String owner = owner();
        LocalDateTime until = now.plus(Duration.ofMillis(Math.max(1L, configuredLeaseDurationMs)));

        Query select = entityManager.createNativeQuery("""
                SELECT id FROM outbox
                 WHERE event_type = :eventType
                   AND status = 'PENDING'
                   AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                   AND (lease_until IS NULL OR lease_until <= :now)
                 ORDER BY created_at ASC, id ASC
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
                """);
        select.setParameter("eventType", eventType);
        select.setParameter("now", now);
        select.setParameter("limit", limit);

        List<?> rawIds = select.getResultList();
        if (rawIds.isEmpty()) {
            return List.of();
        }

        Map<Long, String> tokens = new HashMap<>();
        for (Object rawId : rawIds) {
            Long id = ((Number) rawId).longValue();
            String token = UUID.randomUUID().toString();
            Query update = entityManager.createNativeQuery("""
                    UPDATE outbox
                       SET lease_owner = :owner,
                           lease_token = :token,
                           lease_until = :leaseUntil
                     WHERE id = :id
                       AND status = 'PENDING'
                       AND (lease_until IS NULL OR lease_until <= :now)
                    """);
            update.setParameter("owner", owner);
            update.setParameter("token", token);
            update.setParameter("leaseUntil", until);
            update.setParameter("id", id);
            update.setParameter("now", now);
            if (update.executeUpdate() == 1) {
                tokens.put(id, token);
            }
        }
        if (tokens.isEmpty()) {
            return List.of();
        }

        Iterable<OutboxEvent> loaded = repository.findAllById(tokens.keySet());
        Map<Long, OutboxEvent> loadedById = new HashMap<>();
        for (OutboxEvent event : loaded) {
            if (tokens.containsKey(event.getId())) {
                loadedById.put(event.getId(), event);
            }
        }
        java.util.ArrayList<OutboxEvent> events = new java.util.ArrayList<>();
        for (Object rawId : rawIds) {
            Long id = ((Number) rawId).longValue();
            OutboxEvent event = loadedById.get(id);
            if (event != null) {
                event.claim(owner, tokens.get(id), until);
                events.add(event);
            }
        }
        return events;
    }

    /** Marks a claimed event SENT only if this worker still owns its token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(OutboxEvent event) {
        return finish(event, OutboxEvent.STATUS_SENT, null, null);
    }

    /** Records a retry only if this worker still owns its token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordRetry(OutboxEvent event, String error, LocalDateTime nextAttemptAt) {
        return finish(event, OutboxEvent.STATUS_PENDING, error, nextAttemptAt);
    }

    /** Marks a claimed event permanently failed only if this worker owns its token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, String error) {
        return finish(event, OutboxEvent.STATUS_FAILED, error, null);
    }

    /** Atomically requeues a failed row; concurrent redrives cannot reset it twice. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requeueFailed(OutboxEvent event) {
        return repository.requeueFailed(event.getId()) == 1;
    }

    private boolean finish(OutboxEvent event, String status, String error, LocalDateTime nextAttemptAt) {
        String token = event.getLeaseToken();
        if (token == null || event.getId() == null) {
            return false;
        }
        boolean updated = repository.finishClaim(event.getId(), token, status, error, nextAttemptAt,
                LocalDateTime.now()) == 1;
        if (!updated) {
            return false;
        }
        switch (status) {
            case OutboxEvent.STATUS_SENT -> event.markSent();
            case OutboxEvent.STATUS_FAILED -> event.markFailed(error);
            default -> event.recordRetry(error, nextAttemptAt);
        }
        event.clearLease();
        return true;
    }

    private String owner() {
        return configuredOwner == null || configuredOwner.isBlank()
                ? runtimeOwner
                : configuredOwner.trim();
    }
}
