package com.shifa.oms.ledger.statements;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.FinancialYear;
import com.shifa.oms.ledger.FinancialYearRepository;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.LedgerAccountRepository;
import com.shifa.oms.ledger.OpeningBalance;
import com.shifa.oms.ledger.OpeningBalanceRepository;
import com.shifa.oms.ledger.VoucherLine;
import com.shifa.oms.ledger.VoucherLineRepository;
import com.shifa.oms.ledger.autopost.ControlAccount;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.DrCr;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared per-account data access for the Financial Statements (Balance Sheet, Profit &amp; Loss, and
 * Cash Flow), Financial Statements Reqs 2.1, 2.2, 4.1, 9.2, 12.4.
 *
 * <p>This loader is the <strong>single aggregation path</strong> for the statement services: for a
 * resolved reporting {@link Period} it produces the very same per-account activity the Phase 1
 * {@link com.shifa.oms.ledger.TrialBalanceService} is built from, so every statement reconciles with
 * the Trial Balance <em>by construction</em> (Req 9) rather than by a second, fallible calculation.
 *
 * <p>It reuses <em>exactly</em> the Trial Balance construction: it batch-loads (no N+1) the opening
 * balances for the period's financial year (resolving the opening FY the same way
 * {@code TrialBalanceService.openingFinancialYearId} does), every posted {@link VoucherLine} in
 * {@code [from, to]} grouped by ledger, and the ledgers + groups needed to derive natures; each
 * ledger's opening signed balance and in-period net movement come from the same
 * {@link BalanceMath#signedDelta} calls (Req 2.2). For every ledger with an opening balance
 * <strong>or</strong> a posted line in the period (Req 2.1) it emits a {@link LedgerActivity} whose
 * {@link LedgerActivity#closingSigned()} is the closing balance as at the period's to-date, and whose
 * {@link LedgerActivity#periodMovementSigned()} is the period's net movement (the P&amp;L figure,
 * Req 4.1).
 *
 * <p>Account natures are <em>derived</em> from the owning account group (ledgers store no nature,
 * Req 2.2). This loader owns no {@link java.time.Clock}: current-period defaulting belongs to
 * {@link com.shifa.oms.ledger.ReportPeriodResolver}, and this method receives an already-resolved
 * {@link Period}. Read-only ({@code @Transactional(readOnly = true)}).
 */
@Service
@Transactional(readOnly = true)
public class StatementLedgerLoader {

    /** The seeded ASSET group holding cash ledgers (V55 / {@code LedgerSeedService}). */
    private static final String CASH_GROUP_NAME = "Cash-in-Hand";
    /** The seeded ASSET group holding bank ledgers (V55 / {@code LedgerSeedService}). */
    private static final String BANK_GROUP_NAME = "Bank Accounts";

    private final OpeningBalanceRepository openingBalanceRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final FinancialYearRepository financialYearRepository;

    public StatementLedgerLoader(OpeningBalanceRepository openingBalanceRepository,
                                 VoucherLineRepository voucherLineRepository,
                                 LedgerAccountRepository ledgerAccountRepository,
                                 AccountGroupRepository accountGroupRepository,
                                 FinancialYearRepository financialYearRepository) {
        this.openingBalanceRepository = openingBalanceRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.financialYearRepository = financialYearRepository;
    }

    /**
     * Loads the per-account activity for a resolved reporting period (Reqs 2.1, 2.2, 4.1, 9.2).
     *
     * <p>Reuses the Trial Balance's per-account construction exactly: opening signed balance +
     * &Sigma;{@code signedDelta} over the account's in-period lines, per ledger. Includes every ledger
     * that has an opening balance <em>or</em> a period line (Req 2.1), ordered by ledger name then id
     * for a stable, meaningful presentation.
     *
     * @param period the resolved reporting period (from {@code ReportPeriodResolver})
     * @return the per-account activities for the period (empty when nothing applies)
     */
    public List<LedgerActivity> load(Period period) {
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

        // The involved ledger accounts: those with an opening balance or a line in the period (Req 2.1).
        Set<Long> ledgerIds = new TreeSet<>();
        ledgerIds.addAll(openingByLedger.keySet());
        ledgerIds.addAll(linesByLedger.keySet());
        if (ledgerIds.isEmpty()) {
            return List.of();
        }

        Map<Long, LedgerAccount> ledgersById = ledgerAccountRepository.findAllById(ledgerIds).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        Map<Long, AccountNature> natureByGroup = natureByGroup(ledgersById.values());

        // Order by ledger name then id for a stable, meaningful order (matching the Trial Balance).
        List<Long> orderedLedgerIds = new ArrayList<>(ledgerIds);
        orderedLedgerIds.sort(Comparator
                .comparing((Long id) -> ledgerName(ledgersById.get(id)))
                .thenComparing(Comparator.naturalOrder()));

        List<LedgerActivity> activities = new ArrayList<>(orderedLedgerIds.size());
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

            BigDecimal periodMovementSigned = BigDecimal.ZERO;
            List<VoucherLine> lines = linesByLedger.getOrDefault(ledgerId, List.of());
            for (VoucherLine line : lines) {
                DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
                BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
                if (amount != null) {
                    periodMovementSigned = periodMovementSigned.add(
                            BalanceMath.signedDelta(nature, side, amount));
                }
            }

            activities.add(new LedgerActivity(ledgerId, ledger.getName(), ledger.getAccountGroupId(),
                    nature, openingSigned, periodMovementSigned, opening != null, !lines.isEmpty()));
        }
        return activities;
    }

    /**
     * The financial year whose opening balances apply to the period: the resolved period's FY when it
     * was resolved from an FY id, otherwise the stored FY covering the range start (or {@code null}
     * when no such FY exists yet, so no opening balances contribute). Resolves the opening FY exactly
     * as {@code TrialBalanceService.openingFinancialYearId} does.
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

    // ----------------------------------------------------------------------------------------------
    // Cash / Bank resolver for the Cash Flow statement (Financial Statements Reqs 6.1, 6.2, 9.3).
    // ----------------------------------------------------------------------------------------------

    /**
     * Loads the per-ledger Cash Flow activity for a resolved reporting period (Reqs 6.1, 6.2, 9.3).
     *
     * <p>Resolves the <strong>Cash/Bank ledger set</strong> — every ledger whose owning group is, or
     * nests (directly or via an ancestor) under, the seeded {@code Cash-in-Hand} or {@code Bank
     * Accounts} group (ASSET nature) — then, for each such ledger, computes the split the direct-method
     * {@code CashFlow} builder needs:
     * <ul>
     *   <li>{@code openingSigned}: the signed balance at the start of the period — the FY opening
     *       balance plus the signed movement of the ledger's lines strictly <em>before</em>
     *       {@code period.from()} (bounded to the opening financial year, so a carried opening is never
     *       double-counted);</li>
     *   <li>{@code inflows}: the sum of in-period <strong>debit</strong> movement (cash in, Req 6.2);</li>
     *   <li>{@code outflows}: the sum of in-period <strong>credit</strong> movement (cash out, Req 6.2);</li>
     *   <li>{@code closingSigned()}: {@code openingSigned + inflows − outflows} — the combined closing the
     *       Phase 1 ledger reports at the As_At_Date (Req 9.3), since Cash/Bank ledgers are ASSET nature
     *       (debit is the normal, cash-in side).</li>
     * </ul>
     *
     * <p>Group ancestry is walked with the same map-based approach as
     * {@link com.shifa.oms.ledger.domain.AccountGroupHierarchy}. Ledgers are returned ordered by name
     * for a stable, meaningful presentation. Reuses the same {@code VoucherLineRepository.findForPeriod}
     * finder as {@link #load(Period)} (bounded windows) and the same {@link BalanceMath#signedDelta}
     * sign convention, so the combined closing reconciles with the Trial Balance by construction.
     *
     * @param period the resolved reporting period (from {@code ReportPeriodResolver})
     * @return the per-ledger Cash/Bank activity plus the combined opening, inflows, outflows, and closing
     */
    public CashFlowActivity cashFlowActivity(Period period) {
        List<LedgerAccount> cashBankLedgers = resolveCashBankLedgers();
        if (cashBankLedgers.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new CashFlowActivity(List.of(), zero, zero, zero, zero);
        }

        Set<Long> ledgerIds = cashBankLedgers.stream()
                .map(LedgerAccount::getId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, AccountNature> natureByGroup = natureByGroup(cashBankLedgers);

        // FY opening balances for the period's financial year, scoped to the Cash/Bank ledgers.
        Long openingFyId = openingFinancialYearId(period);
        Map<Long, OpeningBalance> openingByLedger = openingFyId == null
                ? Map.of()
                : openingBalanceRepository.findByFinancialYearId(openingFyId).stream()
                        .filter(ob -> ledgerIds.contains(ob.getLedgerAccountId()))
                        .collect(Collectors.toMap(OpeningBalance::getLedgerAccountId, Function.identity(),
                                (a, b) -> a, LinkedHashMap::new));

        // Pre-period lines: within the opening financial year, strictly before `from` (no cross-FY
        // double-count, since a carried FY opening already incorporates prior years). Reuses findForPeriod.
        LocalDate fyStart = openingFyId == null
                ? null
                : financialYearRepository.findById(openingFyId).map(FinancialYear::getStartDate).orElse(null);
        LocalDate dayBeforeFrom = period.from().minusDays(1);
        Map<Long, List<VoucherLine>> preLinesByLedger = Map.of();
        if (fyStart != null && !fyStart.isAfter(dayBeforeFrom)) {
            preLinesByLedger = voucherLineRepository.findForPeriod(fyStart, dayBeforeFrom).stream()
                    .filter(vl -> ledgerIds.contains(vl.getLedgerAccountId()))
                    .collect(Collectors.groupingBy(VoucherLine::getLedgerAccountId));
        }

        // In-period lines for the Cash/Bank ledgers (single query, filtered in memory — no N+1).
        Map<Long, List<VoucherLine>> periodLinesByLedger = voucherLineRepository
                .findForPeriod(period.from(), period.to()).stream()
                .filter(vl -> ledgerIds.contains(vl.getLedgerAccountId()))
                .collect(Collectors.groupingBy(VoucherLine::getLedgerAccountId));

        List<CashBankLedgerActivity> ledgers = new ArrayList<>(cashBankLedgers.size());
        BigDecimal combinedOpeningSigned = BigDecimal.ZERO;
        BigDecimal totalInflows = BigDecimal.ZERO;
        BigDecimal totalOutflows = BigDecimal.ZERO;
        BigDecimal combinedClosingSigned = BigDecimal.ZERO;

        for (LedgerAccount ledger : cashBankLedgers) {
            long ledgerId = ledger.getId();
            AccountNature nature = natureByGroup.get(ledger.getAccountGroupId());
            if (nature == null) {
                throw new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found.");
            }

            // Opening (period start) signed balance: FY opening + signed movement strictly before `from`.
            OpeningBalance opening = openingByLedger.get(ledgerId);
            BigDecimal openingSigned = opening == null || opening.getAmount() == null
                    ? BigDecimal.ZERO
                    : BalanceMath.signedDelta(nature, opening.getSide(), opening.getAmount());
            for (VoucherLine line : preLinesByLedger.getOrDefault(ledgerId, List.of())) {
                DrCr side = line.getDebit() != null ? DrCr.DEBIT : DrCr.CREDIT;
                BigDecimal amount = line.getDebit() != null ? line.getDebit() : line.getCredit();
                if (amount != null) {
                    openingSigned = openingSigned.add(BalanceMath.signedDelta(nature, side, amount));
                }
            }

            // In-period inflows (debit movement) and outflows (credit movement), Req 6.2.
            BigDecimal inflows = BigDecimal.ZERO;
            BigDecimal outflows = BigDecimal.ZERO;
            for (VoucherLine line : periodLinesByLedger.getOrDefault(ledgerId, List.of())) {
                if (line.getDebit() != null) {
                    inflows = inflows.add(line.getDebit());
                } else if (line.getCredit() != null) {
                    outflows = outflows.add(line.getCredit());
                }
            }

            CashBankLedgerActivity activity = new CashBankLedgerActivity(
                    ledgerId, ledger.getName(), nature, openingSigned, inflows, outflows);
            ledgers.add(activity);
            combinedOpeningSigned = combinedOpeningSigned.add(openingSigned);
            totalInflows = totalInflows.add(inflows);
            totalOutflows = totalOutflows.add(outflows);
            combinedClosingSigned = combinedClosingSigned.add(activity.closingSigned());
        }

        return new CashFlowActivity(List.copyOf(ledgers),
                combinedOpeningSigned, totalInflows, totalOutflows, combinedClosingSigned);
    }

    /**
     * Resolves the Cash/Bank ledger set: every ledger whose owning group is, or nests (directly or via
     * an ancestor) under, the seeded {@code Cash-in-Hand} or {@code Bank Accounts} group (ASSET nature).
     *
     * <p>The root Cash/Bank groups are resolved robustly, consistent with the codebase: primarily via
     * the {@code CASH} / {@code BANK} control ledgers' own groups (stable {@code control_key}s), and
     * additionally by the seeded ASSET group names (so the set is still found if a control ledger were
     * ever re-pointed). Group ancestry is then walked up the {@code group id → parent id} map exactly as
     * {@link com.shifa.oms.ledger.domain.AccountGroupHierarchy} does, so nested Cash/Bank sub-groups are
     * included. Ledgers are returned ordered by name for stable presentation.
     *
     * @return the Cash/Bank ledgers (empty when no Cash/Bank group is present)
     */
    private List<LedgerAccount> resolveCashBankLedgers() {
        Set<Long> rootGroupIds = new HashSet<>();
        ledgerAccountRepository.findByControlKey(ControlAccount.CASH.key())
                .map(LedgerAccount::getAccountGroupId)
                .ifPresent(rootGroupIds::add);
        ledgerAccountRepository.findByControlKey(ControlAccount.BANK.key())
                .map(LedgerAccount::getAccountGroupId)
                .ifPresent(rootGroupIds::add);

        List<AccountGroup> allGroups = accountGroupRepository.findAll();
        for (AccountGroup group : allGroups) {
            if (group.getNature() == AccountNature.ASSET
                    && (CASH_GROUP_NAME.equalsIgnoreCase(group.getName())
                        || BANK_GROUP_NAME.equalsIgnoreCase(group.getName()))) {
                rootGroupIds.add(group.getId());
            }
        }
        if (rootGroupIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> parentByGroup = new HashMap<>();
        for (AccountGroup group : allGroups) {
            parentByGroup.put(group.getId(), group.getParentGroupId());
        }

        Set<Long> cashBankGroupIds = new HashSet<>();
        for (AccountGroup group : allGroups) {
            if (reachesCashBankRoot(group.getId(), parentByGroup, rootGroupIds)) {
                cashBankGroupIds.add(group.getId());
            }
        }

        return ledgerAccountRepository.findAllByOrderByNameAsc().stream()
                .filter(ledger -> cashBankGroupIds.contains(ledger.getAccountGroupId()))
                .toList();
    }

    /**
     * Whether {@code groupId} is, or descends from, one of the {@code rootGroupIds}, by walking up the
     * {@code group id → parent id} map (a {@code null} parent is a root). Mirrors the ancestor-walk of
     * {@link com.shifa.oms.ledger.domain.AccountGroupHierarchy}, carrying a visited set purely as a
     * safety guard against a malformed pre-existing cycle.
     */
    private static boolean reachesCashBankRoot(Long groupId, Map<Long, Long> parentByGroup,
                                               Set<Long> rootGroupIds) {
        Set<Long> visited = new HashSet<>();
        Long current = groupId;
        while (current != null) {
            if (rootGroupIds.contains(current)) {
                return true;
            }
            if (!visited.add(current)) {
                return false;
            }
            current = parentByGroup.get(current);
        }
        return false;
    }

    /**
     * One ledger account's activity for a reporting period (Financial Statements Reqs 2.1, 2.2, 4.1),
     * built from the same opening + net-movement construction the Trial Balance uses.
     *
     * <ul>
     *   <li>{@link #closingSigned()} (opening + movement up to the As_At_Date) feeds the Balance Sheet
     *       and the net-profit computation;</li>
     *   <li>{@link #periodMovementSigned} (the period's net movement) is the Profit &amp; Loss figure
     *       for INCOME/EXPENSE ledgers (Req 4.1).</li>
     * </ul>
     *
     * @param ledgerId             the ledger account id
     * @param ledgerName           the ledger account name (for presentation and stable ordering)
     * @param groupId              the id of the account group this ledger sits directly under
     * @param nature               the account's derived nature
     * @param openingSigned        the signed normal-side opening balance (0 for INCOME/EXPENSE in
     *                             practice)
     * @param periodMovementSigned the &Sigma; {@code signedDelta} over the account's in-period lines
     * @param hasOpeningBalance    whether the account had an opening balance in the period's FY
     * @param hasPeriodLine        whether the account had any posted line in the period
     */
    public record LedgerActivity(long ledgerId, String ledgerName, long groupId, AccountNature nature,
                                 BigDecimal openingSigned, BigDecimal periodMovementSigned,
                                 boolean hasOpeningBalance, boolean hasPeriodLine) {

        /** The closing signed balance as at the period's to-date: {@code openingSigned + periodMovementSigned}. */
        public BigDecimal closingSigned() {
            return openingSigned.add(periodMovementSigned);
        }
    }

    /**
     * One Cash/Bank ledger's direct-method split for a reporting period (Financial Statements Reqs 6.1,
     * 6.2, 9.3). Cash/Bank ledgers are ASSET nature, so debit movement is cash <em>in</em> (inflow) and
     * credit movement is cash <em>out</em> (outflow), and the closing signed balance is
     * {@code openingSigned + inflows − outflows}.
     *
     * @param ledgerId      the Cash/Bank ledger account id
     * @param ledgerName    the ledger account name (for presentation)
     * @param nature        the account's derived nature (ASSET for Cash/Bank ledgers)
     * @param openingSigned the signed balance at the start of the period (opening + movement before {@code from})
     * @param inflows       the sum of in-period debit movement (cash in, non-negative magnitude)
     * @param outflows      the sum of in-period credit movement (cash out, non-negative magnitude)
     */
    public record CashBankLedgerActivity(long ledgerId, String ledgerName, AccountNature nature,
                                         BigDecimal openingSigned, BigDecimal inflows, BigDecimal outflows) {

        /** The closing signed balance as at the period's to-date: {@code openingSigned + inflows − outflows}. */
        public BigDecimal closingSigned() {
            return openingSigned.add(inflows).subtract(outflows);
        }
    }

    /**
     * The combined Cash/Bank activity for a reporting period (Financial Statements Reqs 6.1, 6.2, 6.3,
     * 9.3), ready for the pure direct-method {@code CashFlow} builder: the per-Cash/Bank-ledger splits
     * (drill-down) plus the combined opening, total inflows, total outflows, and combined closing.
     *
     * @param ledgers               the per-Cash/Bank-ledger splits, ordered by ledger name (empty when none)
     * @param combinedOpeningSigned the combined signed opening balance at the start of the period (Req 6.1)
     * @param totalInflows          the combined in-period debit movement (total cash in, Req 6.2)
     * @param totalOutflows         the combined in-period credit movement (total cash out, Req 6.2)
     * @param combinedClosingSigned the combined signed closing balance at the As_At_Date (Reqs 6.1, 9.3)
     */
    public record CashFlowActivity(List<CashBankLedgerActivity> ledgers,
                                   BigDecimal combinedOpeningSigned,
                                   BigDecimal totalInflows,
                                   BigDecimal totalOutflows,
                                   BigDecimal combinedClosingSigned) {

        /** The net cash movement for the period: {@code totalInflows − totalOutflows} (Req 6.3). */
        public BigDecimal netCashMovement() {
            return totalInflows.subtract(totalOutflows);
        }
    }
}
