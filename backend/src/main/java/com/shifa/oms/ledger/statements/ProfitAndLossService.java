package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.ReportPeriodResolver;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.statements.StatementLedgerLoader.LedgerActivity;
import com.shifa.oms.ledger.statements.domain.GroupInput;
import com.shifa.oms.ledger.statements.domain.LedgerBalanceInput;
import com.shifa.oms.ledger.statements.domain.ProfitAndLoss;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds the read-only <strong>Profit &amp; Loss</strong> statement for a reporting period from the
 * Phase 1 General Ledger (Financial Statements, Reqs 1.1, 1.2, 4.1&ndash;4.5, 7.1).
 *
 * <p>The reporting period is resolved by the shared {@link ReportPeriodResolver} (an FY id
 * <em>or</em> an explicit {@code from}/{@code to} range, Reqs 1.1, 1.2; period validation &mdash;
 * neither supplied, or {@code from} after {@code to} &mdash; is delegated to the resolver, so this
 * service holds <strong>no {@link java.time.Clock}</strong>). For that period,
 * {@link StatementLedgerLoader#load(Period)} produces the same per-account activity the Phase 1
 * Trial Balance is built from; this service keeps only the INCOME/EXPENSE ledgers' period
 * <em>net movement</em> ({@link LedgerActivity#periodMovementSigned()}, Req 4.1) and feeds them to
 * the pure {@link ProfitAndLoss#build(List, List)} builder along with the Chart-of-Accounts group
 * forest.
 *
 * <p>When a comparative prior period is requested (Req 7.1) it repeats the computation over the
 * immediately preceding equal-length window &mdash; the window ending the day before the current
 * {@code from} and of the same inclusive length, which for a full Financial-Year request is exactly
 * the prior Financial Year. Read-only ({@code @Transactional(readOnly = true)}); the whole
 * compliance-critical aggregation lives in the pure {@link ProfitAndLoss} builder (Req 12.4).
 */
@Service
@Transactional(readOnly = true)
public class ProfitAndLossService {

    private final ReportPeriodResolver reportPeriodResolver;
    private final StatementLedgerLoader statementLedgerLoader;
    private final AccountGroupRepository accountGroupRepository;

    public ProfitAndLossService(ReportPeriodResolver reportPeriodResolver,
                                StatementLedgerLoader statementLedgerLoader,
                                AccountGroupRepository accountGroupRepository) {
        this.reportPeriodResolver = reportPeriodResolver;
        this.statementLedgerLoader = statementLedgerLoader;
        this.accountGroupRepository = accountGroupRepository;
    }

    /**
     * Compute the Profit &amp; Loss statement for a reporting period (Reqs 1.1, 1.2, 4.1&ndash;4.5,
     * 7.1).
     *
     * @param financialYearId a financial year id, or {@code null} to use the {@code from}/{@code to}
     *                        range (or the current FY when both are {@code null})
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @param comparative     whether to include the immediately preceding equal-length period (Req 7.1)
     * @return the current-period statement, the resolved period metadata, and &mdash; when
     *         {@code comparative} &mdash; the prior-period statement
     */
    public ProfitAndLossResult profitAndLoss(Long financialYearId, LocalDate from, LocalDate to,
                                             boolean comparative) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);
        ProfitAndLoss current = buildFor(period);

        ProfitAndLoss prior = null;
        if (comparative) {
            prior = buildFor(priorPeriod(period));
        }

        return new ProfitAndLossResult(current, period.from(), period.to(), period.financialYearId(),
                comparative, prior);
    }

    /**
     * Build the pure Profit &amp; Loss statement for an already-resolved period: load the per-account
     * activity, keep the INCOME/EXPENSE ledgers' period net movement (Req 4.1), and roll it up under
     * the Chart-of-Accounts group forest.
     */
    private ProfitAndLoss buildFor(Period period) {
        List<LedgerActivity> activities = statementLedgerLoader.load(period);
        List<LedgerBalanceInput> incomeExpenseMovements = activities.stream()
                .filter(activity -> activity.nature() == AccountNature.INCOME
                        || activity.nature() == AccountNature.EXPENSE)
                .map(activity -> new LedgerBalanceInput(activity.ledgerId(), activity.ledgerName(),
                        activity.groupId(), activity.nature(), activity.periodMovementSigned()))
                .toList();
        return ProfitAndLoss.build(incomeExpenseMovements, loadGroups());
    }

    /** The Chart-of-Accounts group forest as pure {@link GroupInput} nodes. */
    private List<GroupInput> loadGroups() {
        return accountGroupRepository.findAll().stream()
                .map(group -> new GroupInput(group.getId(), group.getName(), group.getNature(),
                        group.getParentGroupId()))
                .toList();
    }

    /**
     * The immediately preceding equal-length window (Req 7.1): the window ending the day before the
     * current {@code from} and of the same inclusive length. For a full Financial-Year request this is
     * exactly the prior Financial Year. The {@code financialYearId} is left {@code null} so
     * {@link StatementLedgerLoader#load(Period)} resolves the opening FY from the prior window's start
     * (opening balances do not affect the P&amp;L period movement in any case).
     */
    private static Period priorPeriod(Period period) {
        long lengthDays = ChronoUnit.DAYS.between(period.from(), period.to());
        LocalDate priorTo = period.from().minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(lengthDays);
        return new Period(priorFrom, priorTo, null);
    }
}
