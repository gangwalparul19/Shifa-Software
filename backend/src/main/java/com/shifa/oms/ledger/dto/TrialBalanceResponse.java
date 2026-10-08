package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.TrialBalanceService.TrialBalanceReport;
import com.shifa.oms.ledger.TrialBalanceService.TrialBalanceRow;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DrCr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of the Trial Balance ({@code GET /api/accounting/trial-balance}, Reqs 14.1–14.4, 3.3,
 * 18.2).
 *
 * <p>For a period this returns every ledger account with an opening balance or a posted line
 * (Req 14.1) as a closing {@code (side, magnitude)} row with the debit/credit split pre-resolved,
 * plus the aggregate debit and credit totals, their difference, and whether they balance
 * (Reqs 14.2–14.4). The {@link AccountNature}/{@link DrCr} enums are serialised as their
 * {@code name()}; money is at scale 2.
 *
 * @param from            the inclusive period start
 * @param to              the inclusive period end
 * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
 * @param rows            the per-account closing rows
 * @param debitTotal      the total of every account's closing debit magnitude (Req 14.2)
 * @param creditTotal     the total of every account's closing credit magnitude (Req 14.2)
 * @param difference      {@code debitTotal - creditTotal} — zero exactly when balanced (Req 14.4)
 * @param balanced        whether the debit and credit totals are equal (Reqs 14.3, 18.2)
 */
public record TrialBalanceResponse(
        LocalDate from,
        LocalDate to,
        Long financialYearId,
        List<Row> rows,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        BigDecimal difference,
        boolean balanced
) {

    private static final int MONEY_SCALE = 2;

    /** Maps a {@link TrialBalanceReport} service result to its response view. */
    public static TrialBalanceResponse from(TrialBalanceReport report) {
        return new TrialBalanceResponse(
                report.period().from(),
                report.period().to(),
                report.period().financialYearId(),
                report.rows().stream().map(Row::from).toList(),
                scale(report.debitTotal()),
                scale(report.creditTotal()),
                scale(report.difference()),
                report.balanced());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A single Trial Balance line (Reqs 14.1, 14.2): a ledger account's closing balance as a reporting
     * side and non-negative magnitude, with the debit/credit split pre-resolved for display.
     *
     * @param ledgerId      the ledger account id
     * @param ledgerName    the ledger account name
     * @param nature        the account's derived nature
     * @param side          the side the closing balance rests on
     * @param magnitude     the non-negative closing balance amount (scale 2)
     * @param debitBalance  the closing balance when {@code side == DEBIT}, else zero (scale 2)
     * @param creditBalance the closing balance when {@code side == CREDIT}, else zero (scale 2)
     */
    public record Row(
            long ledgerId,
            String ledgerName,
            AccountNature nature,
            DrCr side,
            BigDecimal magnitude,
            BigDecimal debitBalance,
            BigDecimal creditBalance
    ) {

        /** Maps a {@link TrialBalanceRow} service result to its response view (money at scale 2). */
        public static Row from(TrialBalanceRow row) {
            return new Row(
                    row.ledgerId(),
                    row.ledgerName(),
                    row.nature(),
                    row.side(),
                    scale(row.magnitude()),
                    scale(row.debitBalance()),
                    scale(row.creditBalance()));
        }
    }
}
