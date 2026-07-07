package com.shifa.oms.returns;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link OrderReturn} rows ("operations depth"
 * Feature 1).
 *
 * <p>The filtered finder backs {@code GET /api/admin/returns}: every filter is
 * optional (a {@code null} disables that clause). {@link #existsByOrderIdAndStatusIn}
 * enforces the "one active (non-terminal) return per order" rule at creation.
 */
public interface OrderReturnRepository extends JpaRepository<OrderReturn, Long> {

    /** All returns for a given order, newest first. */
    List<OrderReturn> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** Whether an order already has a return in any of the given (active) statuses. */
    boolean existsByOrderIdAndStatusIn(Long orderId, java.util.Collection<ReturnStatus> statuses);

    /**
     * Filtered, paged return listing. All parameters are optional:
     * <ul>
     *   <li>{@code status} — exact status match;</li>
     *   <li>{@code q} — case-insensitive substring over reason / notes;</li>
     *   <li>{@code from} / {@code to} — inclusive {@code created_at} bounds.</li>
     * </ul>
     */
    @Query("""
            SELECT r FROM OrderReturn r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:from IS NULL OR r.createdAt >= :from)
              AND (:to IS NULL OR r.createdAt <= :to)
              AND (:q IS NULL
                   OR LOWER(r.reason) LIKE CONCAT('%', LOWER(:q), '%')
                   OR LOWER(r.notes) LIKE CONCAT('%', LOWER(:q), '%'))
            """)
    Page<OrderReturn> search(@Param("status") ReturnStatus status,
                             @Param("q") String q,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             Pageable pageable);
}
