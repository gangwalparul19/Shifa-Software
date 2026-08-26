package com.shifa.oms.ledger;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.domain.PostingLine;
import com.shifa.oms.ledger.domain.RunningBalance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only Ledger view (account statement) for the General Ledger (Reqs 3.2, 12.1–12.4).
 *
 * <p>For a single ledger account and a reporting period (a financial year <em>or</em> an explicit
 * date range, Req 4.4), this service produces the account statement the Ledger_View exposes:
 * <ul>
 *   <li>the account's <strong>opening balance</strong> for the period's financial year (Req 3.2);</li>
 *   <li>each voucher line posted to the account within the period, in <strong>chronological
 *       order</strong>, each carrying the <strong>running balance</strong> after it (Reqs 12.1,
 *       12.2);</li>
 *   <li>the <strong>closing balance</strong> — the running balance after the last line, or the
 *       opening balance when there are no lines (Reqs 12.3, 12.4).</li>
 * </ul>
 *
 * <p>The compliance-critical arithmetic lives entirely in the pure {@code ledger.domain} core:
 * the opening magnitude/side is turned into an opening <em>signed</em> balance via
 * {@link BalanceMath#signedDelta}, each persisted {@link VoucherLine} is converted to a pure
 * {@link PostingLine}, and {@link RunningBalance#compute} produces the running and closing balances
 * according to the account's {@linkplain AccountNature nature}. This service only loads data and maps
 * the pure result back onto the persisted lines.
 *
 * <p>The account nature is <em>derived</em> from the owning account group (ledgers store no nature,
 * Req 2.2). The reporting period is resolved by the shared {@link ReportPeriodResolver} (which owns
 * the injectable {@link java.time.Clock} for current-FY defaulting), so no {@code Clock} is needed
 * here. Read-only ({@code @Transactional(readOnly = true)}).
 */
@Service
@Transactional(readOnly = true)
public class LedgerViewService {

    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final OpeningBalanceRepository openingBalanceRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final VoucherRepository voucherRepository;
    private final FinancialYearRepository financialYearRepository;
    private final ReportPeriodResolver reportPeriodResolver;

    public LedgerViewService(LedgerAccountRepository ledgerAccountRepository,
                             AccountGroupRepository accountGroupRepository,
                             OpeningBalanceRepository openingBalanceRepository,
                             VoucherLineRepository voucherLineRepository,
                             VoucherRepository voucherRepository,
                             FinancialYearRepository financialYearRepository,
                             ReportPeriodResolver reportPeriodResolver) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.openingBalanceRepository = openingBalanceRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.voucherRepository = voucherRepository;
        this.financialYearRepository = financialYearRepository;
        this.reportPeriodResolver = reportPeriodResolver;
    }

    /**
     * Builds the account statement for a ledger account over a reporting period (Reqs 3.2, 12.1–12.4).
     *
     * @param ledgerAccountId the ledger account to report on (must exist)
     * @param financialYearId a financial-year id, or {@code null} to use a date range / the current FY
     * @param from            the range start, or {@code null}
     * @param to              the range end, or {@code null}
     * @return the ledger statement: opening balance, chronological rows with running balance, closing
     * @throws ResourceNotFoundException when the ledger account (or its group) does not exist
     */
    public LedgerStatement statement(Long ledgerAccountId, Long financialYearId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");

        LedgerAccount ledger = ledgerAccountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Ledger account " + ledgerAccountId + " was not found."));
        AccountNature nature = natureOf(ledger);

        Period period = reportPeriodResolver.resolve(financialYearId, from, to);

        BigDecimal openingSigned = openingSignedBalance(ledgerAccountId, nature, period);

        List<VoucherLine> lines = voucherLineRepository
                .findForLedgerInPeriodChronological(ledgerAccountId, period.from(), period.to());

        List<PostingLine> postingLines = new ArrayList<>(lines.size());
        for (VoucherLine line : lines) {
            postingLines.add(toPostingLine(ledgerAccountId, nature, line));
        }

        RunningBalance.Result result = RunningBalance.compute(nature, openingSigned, postingLines);

        List<StatementRow> rows = new ArrayList<>(lines.size());
        List<RunningBalance.Entry> entries = result.entries();
        for (int i = 0; i < entries.size(); i++) {
            VoucherLine line = lines.get(i);
            Voucher voucher = voucherRepository.findById(line.getVoucherId()).orElse(null);
            SidedBalance balanceAfter = entries.get(i).balanceAfter(nature);
            rows.add(new StatementRow(voucher, line, balanceAfter));
        }

        return new LedgerStatement(
                ledger.getId(),
                ledger.getName(),
                nature,
                period,
                result.openingBalance(),
                rows,
                result.closingBalance());
    }

    private AccountNature natureOf(LedgerAccount ledger) {
        AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found."));
        return group.getNature();
    }

    /**
     * The account's opening signed balance for the period's financial year (Req 3.2): the stored
     * {@code (amount, side)} turned into a signed balance relative to the nature's normal side. Zero
     * when there is no recorded opening balance (or, for a date-range period, no stored FY covering
     * the range start).
     */
    private BigDecimal openingSignedBalance(Long ledgerAccountId, AccountNature nature, Period period) {
        Long fyId = period.financialYearId();
        if (fyId == null) {
            fyId = financialYearRepository
                    .findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqual(period.from(), period.from())
                    .map(FinancialYear::getId)
                    .orElse(null);
        }
        if (fyId == null) {
            return BigDecimal.ZERO;
        }
        return openingBalanceRepository.findByLedgerAccountIdAndFinancialYearId(ledgerAccountId, fyId)
                .map(ob -> BalanceMath.signedDelta(nature, ob.getSide(), ob.getAmount()))
                .orElse(BigDecimal.ZERO);
    }

    private static PostingLine toPostingLine(Long ledgerAccountId, AccountNature nature, VoucherLine line) {
        boolean isDebit = line.getDebit() != null;
        DrCr drcr = isDebit ? DrCr.DEBIT : DrCr.CREDIT;
        BigDecimal amount = isDebit ? line.getDebit() : line.getCredit();
        return new PostingLine(ledgerAccountId, nature, drcr, amount);
    }

    /**
     * A ledger account statement over a period (Reqs 12.1–12.4): the account context, the resolved
     * period, the opening balance, the chronological rows (each with the running balance after it),
     * and the closing balance.
     *
     * @param ledgerAccountId the ledger account id
     * @param ledgerName      the ledger account name
     * @param nature          the account's derived nature
     * @param period          the resolved reporting period
     * @param opening         the opening balance (side + magnitude) for the period
     * @param rows            the chronological statement rows (empty when no lines in the period)
     * @param closing         the closing balance (equals {@code opening} when there are no rows, Req 12.4)
     */
    public record LedgerStatement(Long ledgerAccountId,
                                  String ledgerName,
                                  AccountNature nature,
                                  Period period,
                                  SidedBalance opening,
                                  List<StatementRow> rows,
                                  SidedBalance closing) {
    }

    /**
     * One row of a ledger statement (Reqs 12.1, 12.2): a posted voucher line, its parent voucher (for
     * date / reference / type / narration), and the account's running balance immediately after the
     * line was applied.
     *
     * @param voucher      the parent voucher of {@code line} (may be {@code null} if not resolvable)
     * @param line         the posted voucher line hitting this account
     * @param balanceAfter the account's running balance (side + magnitude) after this line
     */
    public record StatementRow(Voucher voucher, VoucherLine line, SidedBalance balanceAfter) {
    }
}
