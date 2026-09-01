package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.FinancialYearService;
import com.shifa.oms.ledger.FinancialYearService.FinancialYearWindow;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.ReportPeriodResolver;
import com.shifa.oms.ledger.statements.StatementLedgerLoader.CashBankLedgerActivity;
import com.shifa.oms.ledger.statements.StatementLedgerLoader.CashFlowActivity;
import com.shifa.oms.ledger.statements.domain.CashFlow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds the direct-method <strong>Cash Flow</strong> statement for a reporting period (Financial
 * Statements Reqs 1.1, 1.2, 6.1, 6.2, 6.3, 6.4, 9.3, 7.1).
 *
 * <p>This is a thin, read-only application service over the reused Phase 1 read layer: it resolves
 * the reporting {@link Period} with {@link ReportPeriodResolver} (an FY id or an explicit
 * {@code from}/{@code to} range, with all period validation delegated to it, so Reqs 1.3, 1.4 need no
 * bespoke logic here — Req 1.1, 1.2), asks {@link StatementLedgerLoader#cashFlowActivity(Period)} for
 * the combined Cash/Bank split (the combined pre-period opening, the in-period debit sum = inflows,
 * the in-period credit sum = outflows, and the combined closing to {@code to}), and feeds those into
 * the pure {@link CashFlow#build} builder (Reqs 6.1–6.4, 9.3). It holds <strong>no {@code Clock}</strong>:
 * current-period defaulting belongs entirely to {@code ReportPeriodResolver}.
 *
 * <p>The compliance-critical arithmetic (the {@code opening → inflows → outflows → net → closing}
 * identity and the reconciliation of the computed closing against the independently-loaded ledger
 * closing) lives in the pure {@link CashFlow} domain core, not here; this service only orchestrates
 * data access and, when requested, the comparative prior period.
 *
 * <p><strong>Comparative prior period (Req 7.1).</strong> When {@code comparative} is requested the
 * service also builds the statement for the immediately preceding period of equal length and returns
 * its figures alongside the current ones:
 * <ul>
 *   <li>for an <em>FY request</em> the prior period is the immediately preceding Indian financial year
 *       (the FY ending the day before the current period's start);</li>
 *   <li>for an explicit <em>date range</em> the prior period is the equal-length window ending the day
 *       before {@code from}.</li>
 * </ul>
 * When {@code comparative} is not requested only the current-period figures are returned (Req 7.2:
 * {@link CashFlowResult#priorCashFlow()} is {@code null}).
 *
 * <p>Read-only ({@code @Transactional(readOnly = true)}); every endpoint over this service is a
 * {@code GET}, so the statement is inherently read-only for the CA role (Reqs 10.4, 13).
 */
@Service
@Transactional(readOnly = true)
public class CashFlowService {

    private final ReportPeriodResolver reportPeriodResolver;
    private final StatementLedgerLoader loader;

    public CashFlowService(ReportPeriodResolver reportPeriodResolver, StatementLedgerLoader loader) {
        this.reportPeriodResolver = reportPeriodResolver;
        this.loader = loader;
    }

    /**
     * Builds the Cash Flow statement for a reporting period without the comparative prior period
     * (Req 7.2). Convenience overload of {@link #cashFlow(Long, LocalDate, LocalDate, boolean)}.
     *
     * @param financialYearId a financial year id, or {@code null}
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @return the current-period Cash Flow statement
     */
    public CashFlowResult cashFlow(Long financialYearId, LocalDate from, LocalDate to) {
        return cashFlow(financialYearId, from, to, false);
    }

    /**
     * Builds the direct-method Cash Flow statement for a reporting period, optionally with a
     * comparative prior period (Reqs 1.1, 1.2, 6.1–6.4, 9.3, 7.1).
     *
     * @param financialYearId a financial year id, or {@code null}
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @param comparative     whether to also compute the immediately preceding equal-length period
     * @return the assembled {@link CashFlowResult} (its {@link CashFlowResult#priorCashFlow()} is
     *         {@code null} unless {@code comparative} is requested)
     */
    public CashFlowResult cashFlow(Long financialYearId, LocalDate from, LocalDate to, boolean comparative) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);
        CashFlowActivity activity = loader.cashFlowActivity(period);
        CashFlow cashFlow = CashFlow.build(activity.combinedOpeningSigned(), activity.totalInflows(),
                activity.totalOutflows(), activity.combinedClosingSigned());

        CashFlow priorCashFlow = null;
        if (comparative) {
            CashFlowActivity priorActivity = loader.cashFlowActivity(priorPeriod(period));
            priorCashFlow = CashFlow.build(priorActivity.combinedOpeningSigned(), priorActivity.totalInflows(),
                    priorActivity.totalOutflows(), priorActivity.combinedClosingSigned());
        }

        return new CashFlowResult(period.from(), period.to(), period.financialYearId(), comparative,
                cashFlow, activity.ledgers(), priorCashFlow);
    }

    /**
     * The immediately preceding period of equal length (Req 7.1): the prior Indian financial year for
     * an FY-resolved period, otherwise the equal-length window ending the day before {@code from}.
     * Delegated back through {@link ReportPeriodResolver} so opening-balance resolution stays identical
     * to the current period.
     */
    private Period priorPeriod(Period current) {
        if (current.financialYearId() != null) {
            FinancialYearWindow priorWindow = FinancialYearService.windowFor(current.from().minusDays(1));
            return reportPeriodResolver.resolve(null, priorWindow.startDate(), priorWindow.endDate());
        }
        LocalDate priorTo = current.from().minusDays(1);
        long spanDays = ChronoUnit.DAYS.between(current.from(), current.to());
        LocalDate priorFrom = priorTo.minusDays(spanDays);
        return reportPeriodResolver.resolve(null, priorFrom, priorTo);
    }

    /**
     * The result of computing a Cash Flow statement for a reporting period — the shape consumed by the
     * response DTOs (task 7.1) and the controller (task 8.1).
     *
     * <p>It carries the pure {@link CashFlow} statement (the {@code opening → inflows → outflows → net →
     * closing} figures), the per-Cash/Bank-ledger drill-down (Reqs 5.3, 13.4: each ledger's opening,
     * inflows, outflows, and closing via {@link CashBankLedgerActivity#closingSigned()}), the resolved
     * period metadata, and — when {@code comparative} — the prior-period {@link CashFlow} figures
     * ({@code null} otherwise, Req 7.2).
     *
     * @param from            the resolved inclusive period start
     * @param to              the resolved inclusive period end (the As_At_Date for the closing balance)
     * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
     * @param comparative     whether a comparative prior period was requested
     * @param cashFlow        the current-period direct-method Cash Flow statement
     * @param ledgers         the per-Cash/Bank-ledger split for drill-down, ordered by ledger name
     * @param priorCashFlow   the prior-period Cash Flow statement, or {@code null} when not comparative
     */
    public record CashFlowResult(LocalDate from, LocalDate to, Long financialYearId, boolean comparative,
                                 CashFlow cashFlow, List<CashBankLedgerActivity> ledgers,
                                 CashFlow priorCashFlow) {
    }
}
