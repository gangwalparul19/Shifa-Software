package com.shifa.oms.ledger.dto;

import com.shifa.oms.ledger.LedgerViewService.LedgerStatement;
import com.shifa.oms.ledger.LedgerViewService.StatementRow;
import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherLine;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.domain.VoucherType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of a ledger-account statement ({@code GET /api/accounting/ledgers/{id}/statement},
 * Reqs 3.2, 12.1–12.4).
 *
 * <p>For a ledger account over a period this returns the opening balance, each in-period voucher line
 * in chronological order with the running balance after it, and the closing balance (equal to the
 * opening balance when there are no lines, Req 12.4). Balances are expressed as a {@link Balance}
 * (side + non-negative magnitude); the {@link AccountNature}, {@link DrCr}, and {@link VoucherType}
 * enums are serialised as their {@code name()}; money is at scale 2.
 *
 * @param ledgerAccountId the ledger account id
 * @param ledgerName      the ledger account name
 * @param nature          the account's derived nature
 * @param from            the inclusive period start
 * @param to              the inclusive period end
 * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
 * @param opening         the opening balance for the period
 * @param rows            the chronological statement rows (empty when no lines in the period)
 * @param closing         the closing balance (equals {@code opening} when there are no rows, Req 12.4)
 */
public record LedgerStatementResponse(
        Long ledgerAccountId,
        String ledgerName,
        AccountNature nature,
        LocalDate from,
        LocalDate to,
        Long financialYearId,
        Balance opening,
        List<Row> rows,
        Balance closing
) {

    private static final int MONEY_SCALE = 2;

    /** Maps a {@link LedgerStatement} service result to its response view. */
    public static LedgerStatementResponse from(LedgerStatement statement) {
        return new LedgerStatementResponse(
                statement.ledgerAccountId(),
                statement.ledgerName(),
                statement.nature(),
                statement.period().from(),
                statement.period().to(),
                statement.period().financialYearId(),
                Balance.from(statement.opening()),
                statement.rows().stream().map(Row::from).toList(),
                Balance.from(statement.closing()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A balance expressed as a reporting side and a non-negative magnitude (Reqs 12, 14, 3.4).
     *
     * @param side      the side the balance rests on ({@code DEBIT} or {@code CREDIT})
     * @param magnitude the non-negative absolute balance amount (scale 2)
     */
    public record Balance(DrCr side, BigDecimal magnitude) {

        /** Maps a domain {@link SidedBalance} to its response view (magnitude at scale 2). */
        public static Balance from(SidedBalance balance) {
            if (balance == null) {
                return null;
            }
            return new Balance(balance.side(), scale(balance.magnitude()));
        }
    }

    /**
     * One statement row (Reqs 12.1, 12.2): the posting voucher's metadata, the line's debit/credit
     * amount, and the account's running balance immediately after the line.
     *
     * @param voucherId    the parent voucher id, or {@code null} if unresolved
     * @param reference    the parent voucher reference, or {@code null}
     * @param date         the voucher date, or {@code null}
     * @param type         the voucher type, or {@code null}
     * @param narration    the voucher narration, or {@code null}
     * @param debit        the line's debit amount (scale 2), or {@code null} when this is a credit line
     * @param credit       the line's credit amount (scale 2), or {@code null} when this is a debit line
     * @param balanceAfter the account's running balance after this line
     */
    public record Row(
            Long voucherId,
            String reference,
            LocalDate date,
            VoucherType type,
            String narration,
            BigDecimal debit,
            BigDecimal credit,
            Balance balanceAfter
    ) {

        /** Maps a {@link StatementRow} service result to its response view. */
        public static Row from(StatementRow row) {
            Voucher voucher = row.voucher();
            VoucherLine line = row.line();
            return new Row(
                    voucher == null ? null : voucher.getId(),
                    voucher == null ? null : voucher.getVoucherReference(),
                    voucher == null ? null : voucher.getVoucherDate(),
                    voucher == null ? null : voucher.getVoucherType(),
                    voucher == null ? null : voucher.getNarration(),
                    line == null ? null : scale(line.getDebit()),
                    line == null ? null : scale(line.getCredit()),
                    Balance.from(row.balanceAfter()));
        }
    }
}
