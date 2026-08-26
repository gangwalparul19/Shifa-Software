package com.shifa.oms.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pure ledger-view running-balance computation (General Ledger, Reqs 3.2, 12.1–12.4).
 *
 * <p>Given a single ledger account's {@linkplain AccountNature nature}, its <em>opening signed
 * balance</em> for a period (relative to the nature's {@linkplain AccountNature#normalSide() normal
 * balance side}, per {@link BalanceMath}), and the posted {@link PostingLine voucher lines} that hit
 * that account within the period <strong>in chronological order</strong>, this produces the account
 * statement building blocks the Ledger_View needs:
 * <ul>
 *   <li>one {@link Entry} per line, carrying the line and the signed running balance
 *       <em>after</em> applying it (Reqs 12.1, 12.2);</li>
 *   <li>the {@code closingSignedBalance} — the running balance after the last line (Req 12.3), which
 *       equals the opening balance exactly when there are no lines (Req 12.4).</li>
 * </ul>
 *
 * <p>Each line is applied to the running balance with {@link BalanceMath#applyToBalance} using the
 * <em>account's</em> nature (all lines here belong to the same ledger account), so a debit or credit
 * increases or decreases the balance according to the account's nature (Req 12.2). Every balance is
 * normalised to the codebase-wide money scale (2, {@code HALF_UP}); because the arithmetic is purely
 * additive over amounts already at that scale, no rounding is introduced.
 *
 * <p>This class is final with a private constructor and only static methods — Spring-free, exception
 * free for well-formed posted lines, and fully unit- and property-testable.
 */
public final class RunningBalance {

    /** Scale used for money balances, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    private RunningBalance() {
    }

    /**
     * One row of a ledger account statement: a posted voucher line and the account's signed running
     * balance immediately after that line was applied (Reqs 12.1, 12.2).
     *
     * @param line               the posted voucher line (chronological)
     * @param signedBalanceAfter the account's signed running balance after applying {@code line}
     */
    public record Entry(PostingLine line, BigDecimal signedBalanceAfter) {

        public Entry {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(signedBalanceAfter, "signedBalanceAfter");
        }

        /**
         * The running balance after this line expressed as a reporting side + magnitude (Req 12.2),
         * for the account of the given {@code nature}.
         */
        public BalanceMath.SidedBalance balanceAfter(AccountNature nature) {
            return BalanceMath.closingSide(nature, signedBalanceAfter);
        }
    }

    /**
     * The full result of a running-balance computation (Reqs 12.1–12.4): the account nature, the
     * opening and closing signed balances, and the ordered per-line entries.
     *
     * @param nature               the ledger account's nature
     * @param openingSignedBalance the opening signed balance for the period
     * @param closingSignedBalance the closing signed balance (running balance after the last line, or
     *                             the opening balance when there are no lines — Reqs 12.3, 12.4)
     * @param entries              the ordered per-line entries (never {@code null}; empty when there
     *                             are no lines)
     */
    public record Result(AccountNature nature, BigDecimal openingSignedBalance,
                         BigDecimal closingSignedBalance, List<Entry> entries) {

        public Result {
            Objects.requireNonNull(nature, "nature");
            Objects.requireNonNull(openingSignedBalance, "openingSignedBalance");
            Objects.requireNonNull(closingSignedBalance, "closingSignedBalance");
            entries = (entries == null) ? List.of() : Collections.unmodifiableList(new ArrayList<>(entries));
        }

        /** The opening balance expressed as a reporting side + magnitude. */
        public BalanceMath.SidedBalance openingBalance() {
            return BalanceMath.closingSide(nature, openingSignedBalance);
        }

        /** The closing balance expressed as a reporting side + magnitude (Req 12.3). */
        public BalanceMath.SidedBalance closingBalance() {
            return BalanceMath.closingSide(nature, closingSignedBalance);
        }
    }

    /**
     * Compute the running balance for a ledger account over a period.
     *
     * @param nature               the ledger account's nature (must not be {@code null})
     * @param openingSignedBalance the opening signed balance for the period (must not be {@code null})
     * @param lines                the posted voucher lines hitting the account within the period, in
     *                             chronological order ({@code null} is treated as no lines)
     * @return the ordered per-line entries plus the opening and closing signed balances
     */
    public static Result compute(AccountNature nature, BigDecimal openingSignedBalance, List<PostingLine> lines) {
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(openingSignedBalance, "openingSignedBalance");

        BigDecimal running = openingSignedBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<Entry> entries = new ArrayList<>();

        if (lines != null) {
            for (PostingLine line : lines) {
                if (line == null) {
                    continue;
                }
                BigDecimal amount = line.amount() == null ? BigDecimal.ZERO : line.amount();
                running = BalanceMath.applyToBalance(running, nature, line.drcr(), amount)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                entries.add(new Entry(line, running));
            }
        }

        return new Result(nature, openingSignedBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP), running, entries);
    }
}
