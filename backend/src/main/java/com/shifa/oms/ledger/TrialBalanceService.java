package com.shifa.oms.ledger;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.domain.TrialBalance;
import com.shifa.oms.ledger.domain.TrialBalance.AccountActivity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only Trial Balance service for the General Ledger (Reqs 3.3, 14.1–14.4, 18.2).
 *
 * <p>For a reporting period (a financial-year id <em>or</em> an explicit {@code from}/{@code to}
 * date range, defaulting to the current financial year — resolved by the shared
 * {@link ReportPeriodResolver}, Req 4.4), this service produces the Trial Balance the Chartered
 * Accountant relies on: for every ledger account that has an opening balance in the period's
 * financial year <em>or</em> at least one posted voucher line in the period (Req 14.1), it resolves
 * the account's <strong>closing</strong> balance into a reporting {@code (DrCr, magnitude)} row and
 * totals the closing debit and credit magnitudes across all accounts, reporting their difference
 * (Reqs 14.2–14.4, 18.2).
 *
 * <p>All compliance-critical arithmetic lives in the pure {@code ledger.domain} core: each account's
 * stored opening balance is turned into an opening <em>signed</em> balance via
 * {@link BalanceMath#signedDelta}, each in-period {@link VoucherLine} contributes a signed delta, and
 * {@link TrialBalance#compute(java.util.Collection)} aggregates the per-account closing balances and
 * totals. This service only loads data and maps the pure result onto ledger identities.
 *
 * <p>Account natures are <em>derived</em> from the owning account group (ledgers store no nature,
 * Req 2.2). Opening balances, in-period lines, ledgers, and groups are all batch-loaded to avoid an
 * N+1. The reporting period (and its current-FY {@link java.time.Clock} default) is owned by
 * {@link ReportPeriodResolver}, so no {@code Clock} is needed here. Read-only
 * ({@code @Transactional(readOnly = true)}).
 */
@Service
@Transactional(readOnly = true)
public class TrialBalanceService {

    private final ReportPeriodResolver reportPeriodResolver;
    private final OpeningBalanceRepository openingBalanceRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final FinancialYearRepository financialYearRepository;

    public TrialBalanceService(ReportPeriodResolver reportPeriodResolver,
                               OpeningBalanceRepository openingBalanceRepository,
                               VoucherLineRepository voucherLineRepository,
                               LedgerAccountRepository ledgerAccountRepository,
                               AccountGroupRepository accountGroupRepository,
                               FinancialYearRepository financialYearRepository) {
        this.reportPeriodResolver = reportPeriodResolver;
        this.openingBalanceRepository = openingBalanceRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.financialYearRepository = financialYearRepository;
    }

    /**
     * Builds the Trial Balance for a reporting period (Reqs 3.3, 14.1–14.4, 18.2).
     *
     * @param financialYearId a financial-year id, or {@code null} to use a date range / the current FY
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @return the resolved period plus the per-account closing rows, debit/credit totals, difference,
     *         and whether the trial balance balances
     */
    public TrialBalanceReport trialBalance(Long financialYearId, LocalDate from, LocalDate to) {
        Period period = reportPeriodResolver.resolve(financialYearId, from, to);

        // Opening balances for the period's financial year (fall back to the FY covering the range start).
        Long openingFyId = openingFinancialYearId(period);
        List<OpeningBalance> openings = openingFyId == null
                ? List.of()
                : openingBalanceRepository.findByFinancialYearId(openingFyId);
        Map<Long, OpeningBalance> openingByLedger = openings.stream()
                .collect(Collectors.toMap(OpeningBalance::getLedgerAccountId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        // All posted lines in the period, grouped by ledger account (single query, no N+1).
        Map<Long, List<VoucherLine>> linesByLedger = voucherLineRepository
                .findForPeriod(period.from(), period.to()).stream()
                .collect(Collectors.groupingBy(VoucherLine::getLedgerAccountId));

        // The involved ledger accounts: those with an opening balance or a line in the period (Req 14.1).
        Set<Long> ledgerIds = new TreeSet<>();
        ledgerIds.addAll(openingByLedger.keySet());
        ledgerIds.addAll(linesByLedger.keySet());
        if (ledgerIds.isEmpty()) {
            return new TrialBalanceReport(period, List.of(),
                    scaleZero(), scaleZero(), scaleZero(), true);
        }

        Map<Long, LedgerAccount> ledgersById = ledgerAccountRepository.findAllById(ledgerIds).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        Map<Long, AccountNature> natureByGroup = natureByGroup(ledgersById.values());

        // Build the pure per-account activity, ordered by ledger name then id for a stable, meaningful order.
        List<Long> orderedLedgerIds = new ArrayList<>(ledgerIds);
        orderedLedgerIds.sort(Comparator
                .comparing((Long id) -> ledgerName(ledgersById.get(id)))
                .thenComparing(Comparator.naturalOrder()));

        List<AccountActivity> activities = new ArrayList<>(orderedLedgerIds.size());
        for (Long ledgerId : orderedLedgerIds) {
            LedgerAccount ledger = ledgersById.get(ledgerId);
            if (ledger == null) {
                continue;
            }
            AccountNature nature = natureByGroup.get(ledger.getAccountGroupId());
            if (nature == null) {
                throw new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found.");
            }

            OpeningBalance opening = openingByLedger.get(ledgerId);
            BigDecimal openingSigned = opening == null || opening.getAmount() == null
                    ? BigDecimal.ZERO
                    : BalanceMath.signedDelta(nature, opening.getSide(), opening.getAmount());

            BigDecimal netMovementSigned = BigDecimal.ZERO;
            List<VoucherLine> lines = linesByLedger.getOrDefault(ledgerId, List.of());
            for (VoucherLine line : lines) {
                DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
                BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
                if (amount != null) {
                    netMovementSigned = netMovementSigned.add(BalanceMath.signedDelta(nature, side, amount));
                }
            }

            activities.add(new AccountActivity(ledgerId, nature, openingSigned, netMovementSigned,
                    opening != null, !lines.isEmpty()));
        }

        TrialBalance trialBalance = TrialBalance.compute(activities);

        List<TrialBalanceRow> rows = new ArrayList<>(trialBalance.rows().size());
        for (TrialBalance.Row row : trialBalance.rows()) {
            rows.add(new TrialBalanceRow(
                    row.ledgerId(),
                    ledgerName(ledgersById.get(row.ledgerId())),
                    row.nature(),
                    row.side(),
                    row.magnitude(),
                    row.debitBalance(),
                    row.creditBalance()));
        }

        return new TrialBalanceReport(period, rows,
                trialBalance.debitTotal(), trialBalance.creditTotal(),
                trialBalance.difference(), trialBalance.balanced());
    }

    /**
     * The financial year whose opening balances apply to the period: the resolved period's FY when it
     * was resolved from an FY id, otherwise the stored FY covering the range start (or {@code null}
     * when no such FY exists yet, so no opening balances contribute).
     */
    private Long openingFinancialYearId(Period period) {
        if (period.financialYearId() != null) {
            return period.financialYearId();
        }
        return financialYearRepository
                .findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(period.from(), period.from())
                .map(FinancialYear::getId)
                .orElse(null);
    }

    /** Resolves each ledger's owning account group to its nature (batch-loaded, group id → nature). */
    private Map<Long, AccountNature> natureByGroup(Iterable<LedgerAccount> ledgers) {
        Set<Long> groupIds = new TreeSet<>();
        for (LedgerAccount ledger : ledgers) {
            groupIds.add(ledger.getAccountGroupId());
        }
        return accountGroupRepository.findAllById(groupIds).stream()
                .collect(Collectors.toMap(AccountGroup::getId, AccountGroup::getNature));
    }

    private static String ledgerName(LedgerAccount ledger) {
        return ledger == null ? "" : ledger.getName();
    }

    private static BigDecimal scaleZero() {
        return BigDecimal.ZERO.setScale(2);
    }

    /**
     * A single Trial Balance line (Reqs 14.1, 14.2): a ledger account's closing balance as a reporting
     * side and non-negative magnitude, with the debit/credit split pre-resolved for display.
     *
     * @param ledgerId      the ledger account id
     * @param ledgerName    the ledger account name
     * @param nature        the account's derived nature
     * @param side          the side the closing balance rests on ({@code DEBIT} or {@code CREDIT})
     * @param magnitude     the non-negative closing balance amount at money scale
     * @param debitBalance  the closing balance amount when {@code side == DEBIT}, else zero
     * @param creditBalance the closing balance amount when {@code side == CREDIT}, else zero
     */
    public record TrialBalanceRow(long ledgerId,
                                  String ledgerName,
                                  AccountNature nature,
                                  DrCr side,
                                  BigDecimal magnitude,
                                  BigDecimal debitBalance,
                                  BigDecimal creditBalance) {
    }

    /**
     * The cohesive Trial Balance result (Reqs 14.1–14.4, 18.2): the resolved period, the per-account
     * closing rows, the aggregated debit/credit totals, their difference, and whether it balances.
     *
     * @param period      the resolved reporting period
     * @param rows        the per-account closing rows (accounts with an opening balance or a period line)
     * @param debitTotal  the total of every included account's closing debit magnitude (Req 14.2)
     * @param creditTotal the total of every included account's closing credit magnitude (Req 14.2)
     * @param difference  {@code debitTotal - creditTotal} — zero exactly when balanced (Req 14.4)
     * @param balanced    whether the debit and credit totals are equal (Reqs 14.3, 18.2)
     */
    public record TrialBalanceReport(Period period,
                                     List<TrialBalanceRow> rows,
                                     BigDecimal debitTotal,
                                     BigDecimal creditTotal,
                                     BigDecimal difference,
                                     boolean balanced) {
    }
}
