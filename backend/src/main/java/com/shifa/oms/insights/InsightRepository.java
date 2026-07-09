package com.shifa.oms.insights;

import com.shifa.oms.insights.domain.InsightScope;
import com.shifa.oms.insights.domain.InsightType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the {@link InsightEntity} (design &sect;Persistence).
 *
 * <p>Reads are keyed on {@code computed_date} — the dashboard and API serve the
 * latest computed date — while {@link #deleteByComputedDate(LocalDate)} plus a
 * fresh {@code saveAll} give the idempotent per-date recompute (Req 1.2): a
 * second run for the same date replaces its rows rather than duplicating them,
 * protected by the {@code ux_insights_natural} unique index. The natural-key
 * finder supports notification de-dup (Req 10.1).
 */
public interface InsightRepository extends JpaRepository<InsightEntity, Long> {

    /** The most recent {@code computed_date} present, or empty when no insights exist. */
    @Query("select max(i.computedDate) from InsightEntity i")
    Optional<LocalDate> findMaxComputedDate();

    /**
     * Removes all insights computed for {@code d} (idempotent recompute, Req 1.2).
     *
     * <p>Implemented as a bulk {@code @Modifying} JPQL delete so the {@code DELETE}
     * runs immediately against the database. A derived {@code deleteBy...} would
     * defer the removal to flush time, where Hibernate's action queue executes the
     * fresh {@code saveAll} inserts <em>before</em> the deletes — colliding with the
     * existing rows on the {@code ux_insights_natural} unique index. Flushing the
     * delete up-front avoids that duplicate-key failure on a same-day re-run.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from InsightEntity i where i.computedDate = :d")
    void deleteByComputedDate(LocalDate d);

    /** All insights for a date, ordered by severity then newest id first. */
    List<InsightEntity> findByComputedDateOrderBySeverityAscIdDesc(LocalDate d);

    /** The non-dismissed insights for a date (the default listing, Req 9.1, 9.3). */
    List<InsightEntity> findByComputedDateAndDismissedFalse(LocalDate d);

    /** The insights for a date scoped to a specific entity (salesperson scoping, Req 9.2). */
    List<InsightEntity> findByComputedDateAndScopeAndScopeRefId(
            LocalDate d, InsightScope scope, Long scopeRefId);

    /** The single insight for a natural key, if present (notification de-dup, Req 10.1). */
    Optional<InsightEntity> findByInsightTypeAndScopeAndScopeRefIdAndComputedDate(
            InsightType type, InsightScope scope, Long scopeRefId, LocalDate d);
}
