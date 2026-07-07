package com.shifa.oms.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link Expense} rows (Feature C3).
 *
 * <p>The filtered finder backs {@code GET /api/admin/expenses}; the window
 * finder backs the P&amp;L expense summation. Both filter/sum on
 * {@code incurred_on} (the business date), not {@code created_at}.
 */
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /**
     * Filtered, paged expense listing. All parameters are optional:
     * <ul>
     *   <li>{@code category} — exact category match;</li>
     *   <li>{@code from} / {@code to} — inclusive {@code incurred_on} bounds.</li>
     * </ul>
     */
    @Query("""
            SELECT e FROM Expense e
            WHERE (:category IS NULL OR e.category = :category)
              AND (:from IS NULL OR e.incurredOn >= :from)
              AND (:to IS NULL OR e.incurredOn <= :to)
            """)
    Page<Expense> search(@Param("category") String category,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         Pageable pageable);

    /** All expenses incurred within an inclusive date window, used by the P&amp;L report. */
    List<Expense> findByIncurredOnBetween(LocalDate from, LocalDate to);
}
