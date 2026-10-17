package com.shifa.oms.performance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for monthly sales targets (FEATURE-ROADMAP §6.1).
 */
public interface SalesTargetRepository extends JpaRepository<SalesTarget, Long> {

    /** All targets set for a given month (first-of-month key). */
    List<SalesTarget> findByPeriodMonth(LocalDate periodMonth);

    /** A specific salesperson's target for a month, if set. */
    Optional<SalesTarget> findBySalespersonIdAndPeriodMonth(Long salespersonId, LocalDate periodMonth);

    // --- CRM expansion Phase 2 (Req 12): period-type-aware finders ----------

    /**
     * Per-salesperson targets for a period (type + anchor). Used by the monthly
     * list and the new weekly list; a team target (salesperson_id NULL) is
     * excluded because this filters on a non-null salesperson id.
     */
    List<SalesTarget> findByPeriodTypeAndPeriodMonth(TargetPeriodType periodType, LocalDate periodMonth);

    /** A specific salesperson's target for a period (type + anchor), if set. */
    Optional<SalesTarget> findBySalespersonIdAndPeriodTypeAndPeriodMonth(
            Long salespersonId, TargetPeriodType periodType, LocalDate periodMonth);

    /** A team's target for a period (keyed by team lead), if set. */
    Optional<SalesTarget> findByTeamLeadIdAndPeriodTypeAndPeriodMonth(
            Long teamLeadId, TargetPeriodType periodType, LocalDate periodMonth);
}
