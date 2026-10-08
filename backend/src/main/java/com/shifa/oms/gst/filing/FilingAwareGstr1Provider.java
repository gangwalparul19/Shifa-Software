package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.Gstr1ReturnService;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The <strong>single figure source</strong> for a period's GSTR-1 return (GST returns &amp; filing,
 * Reqs 2.2, 5.4, 6.3).
 *
 * <p>{@link #forPeriod(ReturnPeriod)} returns the one {@link Gstr1Return} that every downstream
 * consumer — the filing-aware export (task 6.3), the ledger reconciliation (task 4.11), and the
 * read-side DTO — must agree on:
 *
 * <ul>
 *   <li>When GSTR-1 for the period is <strong>FILED</strong>, the figures are locked: they are served
 *       <em>verbatim</em> from the immutable filing snapshot via
 *       {@link FilingSnapshotService#currentGstr1Return(ReturnPeriod)} with <strong>no
 *       recomputation</strong> (Reqs 2.2, 5.4). This guarantees a filed period always presents the
 *       exact figures that were filed, regardless of later order/return changes.</li>
 *   <li>Otherwise (NOT_STARTED / PREPARED, i.e. not yet locked), the figures are computed fresh from
 *       {@link Gstr1ReturnService#build(LocalDate, LocalDate)} over the period's calendar-month
 *       window.</li>
 * </ul>
 *
 * <p><strong>Filed status</strong> is determined by looking up the {@code return_filings} row for
 * {@code (year, month, GSTR1)} via {@link ReturnFilingRepository}: a missing row or any non-FILED
 * status falls through to the computed path (a missing row denotes {@code NOT_STARTED}, Req 1.2).
 *
 * <p><strong>Defensive fallback (design note).</strong> If the row reports FILED but the current
 * GSTR-1 snapshot is somehow absent (e.g. legacy/partially-migrated data), this provider falls back
 * to the freshly computed return rather than failing, so export/reconciliation/read never break. In
 * the normal flow {@code FilingStatusService.file} always captures a snapshot before flipping the
 * status to FILED (rolling back on assembly failure — Req 5.8), so a FILED period without a snapshot
 * should not occur; the fallback is purely belt-and-suspenders.
 */
@Service
@Transactional(readOnly = true)
public class FilingAwareGstr1Provider {

    private final ReturnFilingRepository returnFilingRepository;
    private final FilingSnapshotService filingSnapshotService;
    private final Gstr1ReturnService gstr1ReturnService;

    public FilingAwareGstr1Provider(ReturnFilingRepository returnFilingRepository,
                                    FilingSnapshotService filingSnapshotService,
                                    Gstr1ReturnService gstr1ReturnService) {
        this.returnFilingRepository = returnFilingRepository;
        this.filingSnapshotService = filingSnapshotService;
        this.gstr1ReturnService = gstr1ReturnService;
    }

    /**
     * The GSTR-1 return for {@code period} from the single figure source: the snapshot-backed return
     * when GSTR-1 is FILED (no recompute), otherwise the freshly computed one (Reqs 2.2, 5.4, 6.3).
     *
     * @param period the return period whose GSTR-1 figures are requested
     * @return the locked (snapshot) or live (computed) {@link Gstr1Return} for the period
     */
    public Gstr1Return forPeriod(ReturnPeriod period) {
        if (isGstr1Filed(period)) {
            Optional<Gstr1Return> snapshot = filingSnapshotService.currentGstr1Return(period);
            if (snapshot.isPresent()) {
                return snapshot.get();
            }
            // Defensive: FILED but no snapshot present — fall through to computed rather than fail.
        }
        return compute(period);
    }

    /**
     * Whether GSTR-1 for the period is FILED (and hence its figures are locked to the snapshot). A
     * missing filing row or any non-FILED status returns {@code false} (a missing row denotes
     * NOT_STARTED, Req 1.2).
     */
    private boolean isGstr1Filed(ReturnPeriod period) {
        return returnFilingRepository
                .findByPeriodYearAndPeriodMonthAndReturnType(period.year(), period.month(), ReturnType.GSTR1)
                .map(filing -> filing.getStatus() == FilingStatus.FILED)
                .orElse(false);
    }

    /** Freshly computes the GSTR-1 return over the period's calendar-month window. */
    private Gstr1Return compute(ReturnPeriod period) {
        YearMonth ym = period.yearMonth();
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        return gstr1ReturnService.build(monthStart, monthEnd);
    }
}
