package com.shifa.oms.platform.outbox;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency-focused unit coverage for the fenced outbox claim protocol.
 * Database integration is represented by the repository atomic-update boundary:
 * a stale token must not be able to complete a row after lease recovery.
 */
class OutboxClaimServiceTest {

    private EntityManager entityManager;
    private Query selectQuery;
    private Query updateQuery;
    private OutboxEventRepository repository;
    private OutboxClaimService service;

    @BeforeEach
    void setUp() {
        entityManager = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class},
                (proxy, method, args) -> {
                    if ("createNativeQuery".equals(method.getName()) && args != null && args.length > 0) {
                        String sql = String.valueOf(args[0]);
                        return sql.startsWith("SELECT id FROM outbox") ? selectQuery : updateQuery;
                    }
                    if ("toString".equals(method.getName())) return "recording-entity-manager";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
                });
        repository = mock(OutboxEventRepository.class);
        service = new OutboxClaimService(entityManager, repository);
        ReflectionTestUtils.setField(service, "configuredBatchSize", 2);
        ReflectionTestUtils.setField(service, "configuredLeaseDurationMs", 60_000L);
        ReflectionTestUtils.setField(service, "configuredOwner", "node-a");
    }

    @Test
    void claimIsBoundedAndUsesExpiredLeaseRecoveryWithFencing() {
        selectQuery = mock(Query.class);
        updateQuery = mock(Query.class);
        when(selectQuery.setParameter(any(String.class), any())).thenReturn(selectQuery);
        when(selectQuery.getResultList()).thenReturn(List.of(101L, 102L));
        when(updateQuery.setParameter(any(String.class), any())).thenReturn(updateQuery);
        when(updateQuery.executeUpdate()).thenReturn(1);

        OutboxEvent first = event(101L);
        OutboxEvent second = event(102L);
        when(repository.findAllById(anyCollection())).thenReturn(List.of(first, second));

        List<OutboxEvent> claimed = service.claimDue(
                OutboxEvent.EVENT_EMAIL_NOTIFY, LocalDateTime.of(2026, 10, 30, 12, 0));

        assertThat(claimed).containsExactly(first, second);
        assertThat(first.getLeaseOwner()).isEqualTo("node-a");
        assertThat(first.getLeaseToken()).isNotBlank();
        assertThat(first.getLeaseUntil()).isEqualTo(LocalDateTime.of(2026, 10, 30, 12, 1));
        assertThat(second.getLeaseToken()).isNotEqualTo(first.getLeaseToken());
        verify(selectQuery).setParameter("limit", 2);
    }

    @Test
    void staleWorkerCannotCompleteAfterAnotherWorkerTakesLease() {
        OutboxEvent event = event(201L);
        event.claim("node-a", "stale-token", LocalDateTime.now().plusMinutes(2));
        when(repository.finishClaim(eq(201L), eq("stale-token"), eq(OutboxEvent.STATUS_SENT),
                eq(null), eq(null), any(LocalDateTime.class))).thenReturn(0);

        assertThat(service.markSent(event)).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getLeaseToken()).isEqualTo("stale-token");
    }

    @Test
    void currentWorkerCompletionClearsLeaseAndPreservesRetryLifecycle() {
        OutboxEvent event = event(202L);
        event.claim("node-a", "current-token", LocalDateTime.now().plusMinutes(2));
        LocalDateTime next = LocalDateTime.now().plusSeconds(30);
        when(repository.finishClaim(eq(202L), eq("current-token"), eq(OutboxEvent.STATUS_PENDING),
                eq("temporary"), eq(next), any(LocalDateTime.class))).thenReturn(1);

        assertThat(service.recordRetry(event, "temporary", next)).isTrue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("temporary");
        assertThat(event.getLeaseToken()).isNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(next);
    }

    private static OutboxEvent event(long id) {
        OutboxEvent event = new OutboxEvent(
                OutboxEvent.AGGREGATE_ORDER, id, OutboxEvent.EVENT_EMAIL_NOTIFY, java.util.Map.of());
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }
}
