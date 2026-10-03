package com.shifa.oms.platform.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link OutboxEvent} rows.
 *
 * <p>Producers (e.g. the packing flow, task 12) save events; consumers (the
 * integration drainer in task 14 and the admin SSE publisher in task 19) read
 * pending rows. The finders below give those consumers a simple, indexed way to
 * pull work by status or by aggregate.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Undelivered events of a given status, oldest first (drainer/SSE consumer). */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Count of events of a given type in a given status. Backs the admin
     * dashboard's "WhatsApp notifications sent" activity card (Req 19.6), which
     * counts {@code WHATSAPP_NOTIFY} rows that reached {@code SENT}.
     */
    long countByEventTypeAndStatus(String eventType, String status);

    /** All events of a given type for one aggregate (e.g. ORDER_PACKED for an order). */
    List<OutboxEvent> findByAggregateTypeAndAggregateIdAndEventType(
            String aggregateType, Long aggregateId, String eventType);

    /**
     * All events for one aggregate whose event-type starts with {@code prefix}
     * (e.g. {@code "QUIKSHIPX"} for an order). Used by the per-order QuikShipX
     * retry action to find and re-queue a single stuck order's events.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.aggregateType = :aggregateType
              AND e.aggregateId = :aggregateId
              AND e.eventType LIKE CONCAT(:prefix, '%')
            ORDER BY e.id ASC
            """)
    List<OutboxEvent> findByAggregateAndEventTypePrefix(@Param("aggregateType") String aggregateType,
                                                        @Param("aggregateId") Long aggregateId,
                                                        @Param("prefix") String prefix);

    /**
     * Events of a given type that are due for a (re)delivery attempt: still
     * {@code PENDING} and either never attempted ({@code next_attempt_at} null) or
     * whose backoff window has elapsed. Oldest first, so the drainer processes a
     * fair queue (task 14 courier drainer).
     */
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.eventType = :eventType
              AND e.status = :status
              AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findDue(@Param("eventType") String eventType,
                              @Param("status") String status,
                              @Param("now") LocalDateTime now);

    /**
     * Events of a given type currently in a given status whose event-type name
     * starts with {@code prefix} (e.g. {@code "QUIKSHIPX"}). Used by the
     * self-healing re-drive to find permanently-FAILED QuikShipX events to retry
     * after the courier API recovers from a transient outage. Oldest first.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.status = :status
              AND e.eventType LIKE CONCAT(:prefix, '%')
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findByStatusAndEventTypePrefix(@Param("status") String status,
                                                     @Param("prefix") String prefix);
}
