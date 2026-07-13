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
}
