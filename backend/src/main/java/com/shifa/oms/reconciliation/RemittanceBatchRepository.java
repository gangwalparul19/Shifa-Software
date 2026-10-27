package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Spring Data repository for persisted courier COD remittance import batches (V99). */
public interface RemittanceBatchRepository extends JpaRepository<RemittanceBatch, Long> {

    /** All batches, newest import first — backs the report's batch list. */
    List<RemittanceBatch> findAllByOrderByCreatedAtDescIdDesc();

    /**
     * Batches whose {@code created_at} import timestamp falls in the inclusive
     * window, newest first — used for the date-range-filtered report + weekly KPIs.
     */
    List<RemittanceBatch> findByCreatedAtBetweenOrderByCreatedAtDescIdDesc(LocalDateTime from, LocalDateTime to);

    /**
     * The predominant remitted-date summed money for a window — not used directly
     * (the service aggregates from the lines), kept for potential dashboard reuse.
     */
    @Query("SELECT COALESCE(SUM(b.totalSettled), 0) FROM RemittanceBatch b "
            + "WHERE b.createdAt >= :from AND b.createdAt < :to")
    BigDecimal sumSettledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** All batches remitted on a given date (operator cross-reference). */
    List<RemittanceBatch> findByRemittedDateOrderByCreatedAtDescIdDesc(LocalDate remittedDate);
}
