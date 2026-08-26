package com.shifa.oms.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link FinancialYear} (Req 4.x).
 *
 * <p>Backs financial-year resolution for a voucher date (the FY whose start/end contain the date),
 * closed/open listing, and the next-FY lookup used for closing-balance carry-forward (Req 3.4).
 */
public interface FinancialYearRepository extends JpaRepository<FinancialYear, Long> {

    /**
     * The financial year that contains {@code date}, i.e. {@code start_date <= date <= end_date}
     * (Req 4.2). Passed the same date twice for the two bounds.
     */
    Optional<FinancialYear> findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate startBound,
                                                                                        LocalDate endBound);

    /** The financial year beginning on {@code startDate} (unique), used for next-FY lookup. */
    Optional<FinancialYear> findByStartDate(LocalDate startDate);

    /** All financial years matching the given closed flag. */
    List<FinancialYear> findByClosed(boolean closed);

    /** All financial years, most recent first — the financial-year listing (Req 4). */
    List<FinancialYear> findAllByOrderByStartDateDesc();
}
