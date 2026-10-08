package com.shifa.oms.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * Spring Data repository for {@link AuditEvent} rows.
 *
 * <p>The single filtered finder backs {@code GET /api/admin/audit}: every filter
 * is optional (a {@code null} disables that clause), so one query serves the
 * unfiltered trail and any combination of action / entity-type / free-text /
 * date-range filters. Results are ordered by the caller-supplied {@link Pageable}
 * (default newest-first).
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Filtered, paged audit trail. All parameters are optional:
     * <ul>
     *   <li>{@code action} — exact action verb match;</li>
     *   <li>{@code entityType} — exact entity-type match;</li>
     *   <li>{@code q} — case-insensitive substring over summary / actor username
     *       / entity id;</li>
     *   <li>{@code from} / {@code to} — inclusive {@code created_at} bounds.</li>
     * </ul>
     */
    @Query("""
            SELECT a FROM AuditEvent a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:entityType IS NULL OR a.entityType = :entityType)
              AND (:from IS NULL OR a.createdAt >= :from)
              AND (:to IS NULL OR a.createdAt <= :to)
              AND (:q IS NULL
                   OR LOWER(a.summary) LIKE CONCAT('%', LOWER(:q), '%')
                   OR LOWER(a.actorUsername) LIKE CONCAT('%', LOWER(:q), '%')
                   OR LOWER(a.entityId) LIKE CONCAT('%', LOWER(:q), '%'))
            """)
    Page<AuditEvent> search(@Param("action") String action,
                            @Param("entityType") String entityType,
                            @Param("q") String q,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to,
                            Pageable pageable);

    /**
     * Every audit event recorded for a single entity, in chronological order (oldest first). Backs the
     * ledger voucher audit-trail view ({@code GET /api/accounting/vouchers/{id}/audit}, Req 15.4): pass
     * {@code entityType = ENTITY_VOUCHER} and the voucher id as {@code entityId}.
     */
    java.util.List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(String entityType,
                                                                                   String entityId);
}
