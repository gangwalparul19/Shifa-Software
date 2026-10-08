package com.shifa.oms.ledger;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.FinancialYearService.Period;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Shared reporting-period resolver for the General Ledger read services — the Ledger view, the
 * Day Book, and the Trial Balance (Req 4.4).
 *
 * <p>Each read service accepts <em>either</em> a financial-year id <em>or</em> an explicit
 * {@code from}/{@code to} date range, and this resolver turns those inputs into a concrete
 * {@link Period}:
 * <ul>
 *   <li>a financial-year id → that year's {@code (startDate, endDate)} window (takes precedence when
 *       both a year and a range are supplied);</li>
 *   <li>a complete {@code from}/{@code to} range → that inclusive range (rejecting {@code to} before
 *       {@code from});</li>
 *   <li><strong>neither supplied</strong> → the <em>current</em> financial year, resolved via the
 *       injected {@link Clock} (get-or-create through
 *       {@link FinancialYearService#financialYearFor(LocalDate)} for {@code LocalDate.now(clock)}).</li>
 * </ul>
 *
 * <p>This differs from {@link FinancialYearService#resolvePeriod(Long, LocalDate, LocalDate)}, which
 * throws when nothing is supplied: the read services want a sensible default period rather than an
 * error, so this resolver defaults to the current financial year instead. The concrete range logic
 * (and its validation) is otherwise delegated to {@code FinancialYearService.resolvePeriod} so the
 * two stay consistent.
 *
 * <p>Follows the codebase {@code Clock} dual-constructor convention with {@code @Autowired} on the
 * primary constructor (a startup requirement for two-constructor Spring services) so the "current"
 * financial year is testable.
 */
@Service
@Transactional(readOnly = true)
public class ReportPeriodResolver {

    private final FinancialYearService financialYearService;
    private final Clock clock;

    @Autowired
    public ReportPeriodResolver(FinancialYearService financialYearService) {
        this(financialYearService, Clock.systemDefaultZone());
    }

    ReportPeriodResolver(FinancialYearService financialYearService, Clock clock) {
        this.financialYearService = financialYearService;
        this.clock = clock;
    }

    /**
     * Resolves the reporting period for a read service from either a financial-year id or an explicit
     * date range, defaulting to the current financial year when neither is supplied (Req 4.4).
     *
     * @param financialYearId a financial year id, or {@code null}
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @return the resolved {@code (from, to, financialYearId)} period
     * @throws ValidationException when only one end of the date range is supplied (without a financial
     *                             year), or the range end precedes its start
     */
    public Period resolve(Long financialYearId, LocalDate from, LocalDate to) {
        if (financialYearId != null || (from != null && to != null)) {
            return financialYearService.resolvePeriod(financialYearId, from, to);
        }
        if (from != null || to != null) {
            throw new ValidationException(
                    "Both a from date and a to date are required for a date-range reporting period.");
        }
        FinancialYear current = financialYearService.financialYearFor(LocalDate.now(clock));
        return new Period(current.getStartDate(), current.getEndDate(), current.getId());
    }
}
