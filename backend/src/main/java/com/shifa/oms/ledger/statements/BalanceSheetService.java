package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.ReportPeriodResolver;
import com.shifa.oms.ledger.statements.StatementLedgerLoader.LedgerActivity;
import com.shifa.oms.ledger.statements.domain.BalanceSheet;
import com.shifa.oms.ledger.statements.domain.GroupInput;
import com.shifa.oms.ledger.statements.domain.LedgerBalanceInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Balance Sheet as at a date for a resolved reporting period (Financial Statements,
 * Reqs 1.1, 1.2, 1.5, 2, 3, 7.1).
 *
 * <p>This is a thin, read-only application service: it resolves the reporting period via the reused
 * Phase 1 {@link ReportPeriodResolver} (so period validation — neither FY nor range → 400,
 * {@code from} after {@code to} → 400 — is delegated, Reqs 1.3, 1.4), loads the per-account activity
 * for the period via the shared {@link StatementLedgerLoader} (the same construction the Trial
 * Balance is built from, so the statement reconciles by construction, Req 9), and feeds the pure
 * {@link BalanceSheet} builder — the compliance-critical aggregation law lives entirely in the pure
 * {@code ledger.statements.domain} core (Req 12.4).
 *
 * <p>The As_At_Date is the resolved period's {@link Period#to() to-date} (Req 1.5). Each
 * ASSET/LIABILITY/EQUITY ledger contributes its {@link LedgerActivity#closingSigned() closing signed
 * balance} (opening + movement up to the As_At_Date) to the Balance Sheet; INCOME/EXPENSE ledgers are
 * <em>excluded</em> from both sides (Req 2.4) and instead recognised only through the period's
 * Net_Profit, computed as {@code Σ income period-movement − Σ expense period-movement} (credit-positive,
 * Reqs 3.1, 3.2) and injected into equity by the pure builder.
 *
 * <p>When {@code comparative} is requested (Req 7.1), the same computation is repeated over the
 * <strong>immediately preceding equal-length window</strong> — the window of the same number of days
 * ending the day before the current period's {@code from} — and the prior {@link BalanceSheet} is
 * returned alongside the current one so the DTO mapper can align prior figures node-by-node.
 *
 * <p>Holds <strong>no</strong> {@link java.time.Clock}: current-period defaulting is entirely the
 * {@link ReportPeriodResolver}'s concern. Read-only ({@code @Transactional(readOnly = true)}).
 */
@Service
@Transactional(readOnly = true)
public class BalanceSheetService {

    private final ReportPeriodResolver reportPeriodResolver;
    private final StatementLedgerLoader statementLedgerLoader;
    private final AccountGroupRepository accountGroupRepository;

    public BalanceSheetService(ReportPeriodResolver reportPeriodResolver,
                               StatementLedgerLoader statementLedgerLoader,
                               AccountGroupRepository accountGroupRepository) {
        this.reportPeriodResolver = reportPeriodResolver;
        this.statementLedgerLoader = statementLedgerLoader;
        this.accountGroupRepository = accountGroupRepository;
    }

    /**
     * Compute the Balance Sheet as at the resolved period's to-date, optionally with a comparative
     * prior period (Reqs 1.1, 1.2, 1.5, 2, 3, 7.1).
     *
     * @param financialYearId a financial year id, or {@code null} (resolved by {@link ReportPeriodResolver})
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @param comparative     whether to also compute the immediately preceding equal-length window (Req 7)
     * @return the Balance Sheet result carrying the current (and, when requested, prior) pure
     *         {@link BalanceSheet} plus the period metadata
     * @throws com.shifa.oms.common.ValidationException when the period inputs are invalid (Reqs 1.3, 1.4)
     */
    public BalanceSheetResult balanceSheet(Long financialYearId, LocalDate from, LocalDate to,
                                           boolean comparative) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);

        // Group forest is period-independent; load it once and reuse for the prior window too.
        List<GroupInput> groups = loadGroups();

        BalanceSheet current = buildBalanceSheet(period, groups);
        BalanceSheet prior = null;
        if (comparative) {
            prior = buildBalanceSheet(precedingEqualLengthWindow(period), groups);
        }

        return new BalanceSheetResult(period.to(), period.from(), period.to(), period.financialYearId(),
                comparative, current, prior);
    }

    /**
     * Build the pure {@link BalanceSheet} for one resolved period: split the loaded activities into
     * ASSET/LIABILITY/EQUITY closing balances (fed to the builder) and INCOME/EXPENSE net movements
     * (folded into the credit-positive Net_Profit), then delegate to {@link BalanceSheet#build}.
     */
    private BalanceSheet buildBalanceSheet(Period period, List<GroupInput> groups) {
        List<LedgerActivity> activities = statementLedgerLoader.load(period);

        List<LedgerBalanceInput> balanceSheetLedgers = new ArrayList<>();
        BigDecimal netProfitSigned = BigDecimal.ZERO;
        for (LedgerActivity activity : activities) {
            switch (activity.nature()) {
                case ASSET, LIABILITY, EQUITY -> balanceSheetLedgers.add(new LedgerBalanceInput(
                        activity.ledgerId(), activity.ledgerName(), activity.groupId(),
                        activity.nature(), activity.closingSigned()));
                // Net_Profit = Σ income movement − Σ expense movement (credit-positive): income
                // period-movement is credit-positive and expense period-movement is debit-positive under
                // the BalanceMath normal-side sign convention, so income − expense is credit-positive.
                case INCOME -> netProfitSigned = netProfitSigned.add(activity.periodMovementSigned());
                case EXPENSE -> netProfitSigned = netProfitSigned.subtract(activity.periodMovementSigned());
            }
        }

        return BalanceSheet.build(balanceSheetLedgers, groups, netProfitSigned);
    }

    /**
     * The immediately preceding window of equal length (Req 7.1): the same number of days as the
     * current period, ending the day before the current period's {@code from}. For a standard Indian
     * financial year this yields the prior-year window of identical day-count; for an explicit date
     * range it yields the equal-length window ending the day before {@code from}. The prior period is
     * a raw date range (no financial-year id), so the loader resolves its opening balances from the FY
     * covering its start.
     */
    private static Period precedingEqualLengthWindow(Period period) {
        long spanDays = ChronoUnit.DAYS.between(period.from(), period.to());
        LocalDate priorTo = period.from().minusDays(1);
        LocalDate priorFrom = priorTo.minusDays(spanDays);
        return new Period(priorFrom, priorTo, null);
    }

    /** Load the Chart-of-Accounts group forest as pure {@link GroupInput}s for the builder (Req 5.1, 5.2). */
    private List<GroupInput> loadGroups() {
        List<GroupInput> groups = new ArrayList<>();
        for (AccountGroup group : accountGroupRepository.findAll()) {
            groups.add(new GroupInput(group.getId(), group.getName(), group.getNature(),
                    group.getParentGroupId()));
        }
        return groups;
    }
}
