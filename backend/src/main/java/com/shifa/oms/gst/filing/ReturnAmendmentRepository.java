package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.AmendmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link ReturnAmendment} rows (GST returns &amp; filing, Reqs 3.4, 3.6,
 * 3.7, 3.8).
 *
 * <p>The finders cover exactly what {@code AmendmentService} needs:
 * <ul>
 *   <li>the existing amendment for a {@code (target period, original document)} so repeated
 *       corrections consolidate into one row rather than creating duplicates (Reqs 3.4, 3.8);</li>
 *   <li>amendments by {@link AmendmentStatus} — e.g. the PENDING ones to (re)attribute when a period
 *       opens, or MANUAL_REVIEW ones for CA resolution (Reqs 3.6, 3.7);</li>
 *   <li>amendments by original period for review/reporting against a Filed_Period (Req 3.4).</li>
 * </ul>
 */
public interface ReturnAmendmentRepository extends JpaRepository<ReturnAmendment, Long> {

    /**
     * The amendment already recorded for a target period and original document, if any — the
     * consolidation key so repeated corrections for the same document merge into one row (Reqs 3.4,
     * 3.8).
     */
    Optional<ReturnAmendment> findByTargetPeriodYearAndTargetPeriodMonthAndOriginalDocumentRef(
            Integer targetPeriodYear, Integer targetPeriodMonth, String originalDocumentRef);

    /**
     * All amendments in a given routing status — e.g. PENDING corrections awaiting an open target
     * period (Req 3.6) or MANUAL_REVIEW corrections awaiting CA resolution (Req 3.7).
     */
    List<ReturnAmendment> findByStatus(AmendmentStatus status);

    /**
     * All amendments detected against a given Filed_Period, identified by its original period
     * coordinates (Req 3.4).
     */
    List<ReturnAmendment> findByOriginalPeriodYearAndOriginalPeriodMonth(int originalPeriodYear,
                                                                         int originalPeriodMonth);
}
