package com.shifa.oms.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The pure Trial Balance aggregation (General Ledger, Reqs 3.3, 14.1, 14.2, 14.3, 14.4, 18.2).
 *
 * <p>This is the double-entry proof the Chartered Accountant relies on: given, for each ledger
 * account, its {@linkplain AccountActivity#openingSigned() opening signed balance} and the
 * {@linkplain AccountActivity#netMovementSigned() net signed movement} of its posted lines in the
 * period, {@link #compute(Collection)} resolves each account's <em>closing</em> balance into a
 * reporting {@code (DrCr, magnitude)} {@link Row} (via {@link BalanceMath#closingSide}) and totals
 * the closing debit and credit magnitudes across all accounts, reporting their
 * {@link #difference() difference}.
 *
 * <p>Signed balances follow the {@link BalanceMath} sign convention: a balance is tracked relative
 * to the account's {@linkplain AccountNature#normalSide() normal side}, and closing signed =
 * opening signed + net movement. Only accounts that have an opening balance <em>or</em> at least one
 * voucher line in the period are included (Req 14.1); an account with neither is dropped entirely
 * (its closing is trivially zero and it never appeared in the period).
 *
 * <p>When every voucher in the period is balanced and the opening balances are balanced, each
 * voucher and the opening set contributes equal debit and credit amounts, so the aggregated debit
 * total equals the aggregated credit total and {@link #balanced()} is {@code true} with a zero
 * {@link #difference()} (Reqs 14.3, 18.2). Otherwise the (non-zero) difference is reported (Req
 * 14.4). All totals and magnitudes are normalised to the ledger money scale (2, {@code HALF_UP}).
 *
 * <p>The class is immutable, Spring-free, and computed by a single total, exception-free factory —
 * so the balancing property is trivially property-testable.
 */
public final class TrialBalance {

    /** Scale used for money magnitudes/totals, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    private final List<Row> rows;
    private final BigDecimal debitTotal;
    private final BigDecimal creditTotal;
    private final BigDecimal difference;

    private TrialBalance(List<Row> rows, BigDecimal debitTotal, BigDecimal creditTotal, BigDecimal difference) {
        this.rows = List.copyOf(rows);
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.difference = difference;
    }

    /**
     * Aggregate the given per-account activity into a Trial Balance (Reqs 14.1–14.4, 3.3, 18.2).
     *
     * <p>For each account that {@linkplain AccountActivity#isIncluded() has an opening balance or a
     * line in the period}, computes closing signed = opening + net movement, resolves it to a
     * closing {@code (side, magnitude)} {@link Row}, and adds the magnitude to the debit or credit
     * total according to its side. Accounts are emitted in the iteration order of {@code activities}
     * (the caller supplies a stable, meaningful order). Accounts with neither an opening balance nor
     * a period line are excluded.
     *
     * @param activities the per-account opening balance + net movement inputs (must not be
     *                   {@code null}; {@code null} elements are ignored)
     * @return the computed Trial Balance with per-account closing rows, debit/credit totals, and
     *         their difference
     */
    public static TrialBalance compute(Collection<AccountActivity> activities) {
        Objects.requireNonNull(activities, "activities");
        List<Row> rows = new ArrayList<>();
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (AccountActivity activity : activities) {
            if (activity == null || !activity.isIncluded()) {
                continue;
            }
            BigDecimal closingSigned = activity.openingSigned().add(activity.netMovementSigned());
            BalanceMath.SidedBalance closing = BalanceMath.closingSide(activity.nature(), closingSigned);
            BigDecimal magnitude = closing.magnitude().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            rows.add(new Row(activity.ledgerId(), activity.nature(), closing.side(), magnitude));
            if (closing.side() == DrCr.DEBIT) {
                debitTotal = debitTotal.add(magnitude);
            } else {
                creditTotal = creditTotal.add(magnitude);
            }
        }
        debitTotal = debitTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        creditTotal = creditTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new TrialBalance(rows, debitTotal, creditTotal, debitTotal.subtract(creditTotal));
    }

    /** The per-account closing rows, in the order the accounts were supplied (immutable). */
    public List<Row> rows() {
        return rows;
    }

    /** The total of every included account's closing <strong>debit</strong> magnitude (Req 14.2). */
    public BigDecimal debitTotal() {
        return debitTotal;
    }

    /** The total of every included account's closing <strong>credit</strong> magnitude (Req 14.2). */
    public BigDecimal creditTotal() {
        return creditTotal;
    }

    /**
     * {@code debitTotal - creditTotal} — zero exactly when the Trial Balance balances (Req 14.4).
     */
    public BigDecimal difference() {
        return difference;
    }

    /** Whether the debit and credit totals are equal, i.e. the difference is zero (Reqs 14.3, 18.2). */
    public boolean balanced() {
        return difference.signum() == 0;
    }

    /**
     * One ledger account's activity feeding the Trial Balance: its opening signed balance and the
     * net signed movement of its posted lines in the period, plus whether it qualifies for inclusion
     * (Req 14.1). Signed values follow the {@link BalanceMath} convention (relative to the account's
     * {@linkplain AccountNature#normalSide() normal side}).
     *
     * <p>{@code hasOpeningBalance} and {@code hasPeriodLine} are carried explicitly rather than being
     * inferred from the amounts: an opening balance or a period line can legitimately net to zero, and
     * an account must still be reported when it has either (Req 14.1). A {@code null} opening or
     * movement is treated as {@link BigDecimal#ZERO}.
     *
     * @param ledgerId          the ledger account id
     * @param nature            the account's nature (required)
     * @param openingSigned     the opening signed balance for the period ({@code null} → zero)
     * @param netMovementSigned the sum of signed deltas of the account's posted lines in the period
     *                          ({@code null} → zero)
     * @param hasOpeningBalance whether an opening balance was recorded for this account in the period
     * @param hasPeriodLine     whether the account has any voucher line in the period
     */
    public record AccountActivity(long ledgerId, AccountNature nature, BigDecimal openingSigned,
                                  BigDecimal netMovementSigned, boolean hasOpeningBalance,
                                  boolean hasPeriodLine) {

        public AccountActivity {
            Objects.requireNonNull(nature, "nature");
            openingSigned = openingSigned == null ? BigDecimal.ZERO : openingSigned;
            netMovementSigned = netMovementSigned == null ? BigDecimal.ZERO : netMovementSigned;
        }

        /** Whether this account qualifies for the Trial Balance: it has an opening balance or a line in the period (Req 14.1). */
        public boolean isIncluded() {
            return hasOpeningBalance || hasPeriodLine;
        }
    }

    /**
     * A single Trial Balance line: a ledger account's closing balance expressed as a reporting side
     * and a non-negative magnitude (Req 14.1). Exactly one of the debit/credit sides carries the
     * magnitude; the other is implicitly zero for this account.
     *
     * @param ledgerId  the ledger account id
     * @param nature    the account's nature
     * @param side      the side the closing balance rests on ({@code DEBIT} or {@code CREDIT})
     * @param magnitude the non-negative closing balance amount at money scale
     */
    public record Row(long ledgerId, AccountNature nature, DrCr side, BigDecimal magnitude) {

        public Row {
            Objects.requireNonNull(nature, "nature");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(magnitude, "magnitude");
        }

        /** The closing debit magnitude for this account: the magnitude when {@code side == DEBIT}, else zero. */
        public BigDecimal debitBalance() {
            return side == DrCr.DEBIT ? magnitude : BigDecimal.ZERO;
        }

        /** The closing credit magnitude for this account: the magnitude when {@code side == CREDIT}, else zero. */
        public BigDecimal creditBalance() {
            return side == DrCr.CREDIT ? magnitude : BigDecimal.ZERO;
        }
    }
}
