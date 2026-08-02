package com.shifa.oms.integration;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/** Data access for {@link IntegrationEvent} ({@code integration_events}, V49). */
public interface IntegrationEventRepository extends JpaRepository<IntegrationEvent, Long> {

    /**
     * Resolves an event by its provider identifier, scoped to the provider. This is
     * the duplicate-delivery check (Req 2.6, 6.11).
     */
    Optional<IntegrationEvent> findBySourceAndExternalEventId(IntegrationSource source, String externalEventId);

    boolean existsBySourceAndExternalEventId(IntegrationSource source, String externalEventId);

    /**
     * The admin health-console failure list: unresolved failures inside the
     * retention window, most recent first (Req 14.2).
     */
    Page<IntegrationEvent> findByOutcomeInAndReceivedAtGreaterThanEqualOrderByReceivedAtDescIdDesc(
            Collection<IntegrationOutcome> outcomes, LocalDateTime since, Pageable pageable);

    /** The dashboard's unresolved-failure count (Req 14.7). */
    long countByOutcomeInAndReceivedAtGreaterThanEqual(
            Collection<IntegrationOutcome> outcomes, LocalDateTime since);

    /** Events recorded against one order, for the order-level integration history. */
    Iterable<IntegrationEvent> findByOrderIdOrderByReceivedAtDescIdDesc(Long orderId);
}
