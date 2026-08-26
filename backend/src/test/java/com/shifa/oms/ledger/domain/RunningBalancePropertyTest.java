package com.shifa.oms.ledger.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link RunningBalance} ledger-view computation (General Ledger, design
 * Correctness Property 14).
 *
 * <p>Feature: general-ledger-accounting, Property 14: Ledger running balance is correct.
 *
 * <p><b>Validates: Requirements 3.2, 12.1, 12.2, 12.3, 12.4</b>
 *
 * <p>From an opening signed balance + a chronological list of posted lines for a single ledger
 * account of a given nature, the running balance after each line applies that line's debit/credit
 * per the account's nature (Reqs 12.1, 12.2); the closing balance is the running balance after the
 * last line (Req 12.3); and when there are no lines the closing balance equals the opening balance
 * (Req 12.4). Each of these is checked against an independent oracle.
 */
class RunningBalancePropertyTest {

    /** Codebase-wide money scale (matches {@link RunningBalance}). */
    private static final int MONEY_SCALE = 2;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 14: Ledger running balance is correct
    // **Validates: Requirements 3.2, 12.1, 12.2, 12.3, 12.4**
    // The running balance after each line equals the opening balance with every prior line applied
    // (per the account's nature); the closing balance is the last running balance; empty lines leave
    // closing == opening. Verified against an independent re-implementation of the sign convention.
    // ---------------------------------------------------------------------------------------------

    /**
     * For an arbitrary nature, opening signed balance, and chronological lines, every per-line entry
     * carries the running balance obtained by applying that line and all prior lines to the opening
     * balance according to the account's nature (Reqs 12.1, 12.2), and the closing balance equals the
     * running balance after the last line (Req 12.3).
     */
    @Property(tries = 300)
    void runningBalanceAppliesEachLinePerNature(@ForAll("scenarios") Scenario scenario) {
        RunningBalance.Result result =
                RunningBalance.compute(scenario.nature(), scenario.opening(), scenario.lines());

        // One entry per (non-null) line, preserving order and referencing the same line.
        assertThat(result.entries()).hasSameSizeAs(scenario.lines());

        BigDecimal expected = scenario.opening().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (int i = 0; i < scenario.lines().size(); i++) {
            PostingLine line = scenario.lines().get(i);
            expected = applySigned(expected, scenario.nature(), line);

            RunningBalance.Entry entry = result.entries().get(i);
            assertThat(entry.line()).isSameAs(line);
            assertThat(entry.signedBalanceAfter())
                    .as("running balance after line %d", i)
                    .isEqualByComparingTo(expected);
        }

        // Closing balance is the running balance after the last line (Req 12.3).
        assertThat(result.closingSignedBalance())
                .as("closing == running after last line")
                .isEqualByComparingTo(expected);
        // Opening balance is preserved (normalised) on the result.
        assertThat(result.openingSignedBalance())
                .isEqualByComparingTo(scenario.opening().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * When a ledger account has no voucher lines in the period, the closing balance equals the
     * opening balance and there are no entries (Req 12.4).
     */
    @Property(tries = 200)
    void emptyLinesLeaveClosingEqualToOpening(@ForAll("natures") AccountNature nature,
                                              @ForAll("signedBalances") BigDecimal opening) {
        RunningBalance.Result withEmpty = RunningBalance.compute(nature, opening, List.of());
        RunningBalance.Result withNull = RunningBalance.compute(nature, opening, null);

        for (RunningBalance.Result result : List.of(withEmpty, withNull)) {
            assertThat(result.entries()).isEmpty();
            assertThat(result.closingSignedBalance())
                    .isEqualByComparingTo(opening.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            assertThat(result.closingSignedBalance())
                    .isEqualByComparingTo(result.openingSignedBalance());
        }
    }

    /**
     * A debit and credit of the same amount posted to the same account cancel out: after both lines
     * the running balance returns to the opening balance (a direct consequence of the sign
     * convention, Req 12.2).
     */
    @Property(tries = 200)
    void offsettingDebitAndCreditReturnToOpening(@ForAll("natures") AccountNature nature,
                                                 @ForAll("signedBalances") BigDecimal opening,
                                                 @ForAll("positiveAmounts") BigDecimal amount) {
        List<PostingLine> lines = List.of(
                PostingLine.debit(1L, nature, amount),
                PostingLine.credit(1L, nature, amount));

        RunningBalance.Result result = RunningBalance.compute(nature, opening, lines);

        assertThat(result.closingSignedBalance())
                .isEqualByComparingTo(opening.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    // --- Oracle (independent re-implementation of the sign convention) ---------------------------

    /**
     * Applies a line to a running signed balance: {@code +amount} when the line's side is the
     * nature's normal side, {@code -amount} otherwise. Independent of {@link BalanceMath}.
     */
    private static BigDecimal applySigned(BigDecimal running, AccountNature nature, PostingLine line) {
        BigDecimal amount = line.amount() == null ? BigDecimal.ZERO : line.amount();
        boolean increases = line.drcr() == nature.normalSide();
        BigDecimal delta = increases ? amount : amount.negate();
        return running.add(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // --- Generators ------------------------------------------------------------------------------

    /** All five account natures. */
    @Provide
    Arbitrary<AccountNature> natures() {
        return Arbitraries.of(AccountNature.values());
    }

    /** Strictly positive, scale-2 money amounts. */
    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
    }

    /** Signed, scale-2 opening balances (positive, negative, or zero). */
    @Provide
    Arbitrary<BigDecimal> signedBalances() {
        return Arbitraries.longs().between(-100_000_000L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
    }

    /**
     * A single-account scenario: a nature, a signed opening balance, and 0..8 chronological posting
     * lines (each a debit or credit of a strictly positive amount against the same nature).
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<DrCr> sides = Arbitraries.of(DrCr.values());

        return Combinators.combine(natures(), signedBalances()).as(NatureAndOpening::new)
                .flatMap(no -> Combinators.combine(sides, positiveAmounts())
                        .as((side, amount) -> new PostingLine(1L, no.nature(), side, amount))
                        .list().ofMinSize(0).ofMaxSize(8)
                        .map(lines -> new Scenario(no.nature(), no.opening(), lines)));
    }

    private record NatureAndOpening(AccountNature nature, BigDecimal opening) {
    }

    private record Scenario(AccountNature nature, BigDecimal opening, List<PostingLine> lines) {
        Scenario {
            lines = new ArrayList<>(lines);
        }
    }
}
