package com.shifa.oms.ledger.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link DoubleEntry} imbalance reporting (General Ledger, design Correctness
 * Property 9).
 *
 * <p>Feature: general-ledger-accounting, Property 9: Imbalance is reported with exact totals.
 *
 * <p><b>Validates: Requirements 5.3</b>
 *
 * <p>For any draft voucher whose debit total does not equal its credit total, the rejection reports
 * the debit total, the credit total, and a difference equal to {@code (debit total − credit total)}.
 * This test lives in its own class (separate from other {@link DoubleEntry} tests) so it can be
 * developed and run independently.
 */
class DoubleEntryImbalancePropertyTest {

    /** Codebase-wide money scale (2 decimals, HALF_UP), matching {@link DoubleEntry}. */
    private static final int MONEY_SCALE = 2;

    /**
     * An imbalanced draft plus the independently-computed debit and credit totals it should report,
     * so the property does not rely on {@link DoubleEntry} to compute its own expectation.
     */
    private record ImbalancedDraft(DraftVoucher draft, BigDecimal expectedDebit, BigDecimal expectedCredit) {
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 9: Imbalance is reported with exact totals
    // **Validates: Requirements 5.3**
    // For any draft whose debit total != credit total: validation is rejected, the BalanceCheck
    // carries the exact debit/credit totals and a difference equal to (debitTotal - creditTotal),
    // and a violation message states all three exact figures.
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 200)
    void imbalanceIsReportedWithExactTotals(@ForAll("imbalancedDrafts") ImbalancedDraft sample) {
        DoubleEntry.Result result = DoubleEntry.validate(sample.draft());
        DoubleEntry.BalanceCheck balance = result.balance();

        // The reported totals are exactly the independently-computed sums (Req 5.3).
        assertThat(balance.debitTotal()).isEqualByComparingTo(sample.expectedDebit());
        assertThat(balance.creditTotal()).isEqualByComparingTo(sample.expectedCredit());

        // The difference equals (debit total − credit total) exactly.
        assertThat(balance.difference())
                .isEqualByComparingTo(sample.expectedDebit().subtract(sample.expectedCredit()));

        // An imbalanced draft never balances and is rejected.
        assertThat(balance.balanced()).isFalse();
        assertThat(result.ok()).isFalse();

        // Some violation message reports all three exact figures (Req 5.3).
        assertThat(result.violations()).anySatisfy(message -> assertThat(message)
                .contains(balance.debitTotal().toPlainString())
                .contains(balance.creditTotal().toPlainString())
                .contains(balance.difference().toPlainString()));
    }

    // --- Generators ------------------------------------------------------------------------------

    /**
     * Draft vouchers with at least one debit line and one credit line whose (scale-2) debit and
     * credit totals differ. Amounts are generated at scale 2 so the sums are exact, and each line is
     * otherwise valid (a ledger reference, a nature, and a strictly positive amount) — isolating the
     * imbalance as the sole defect.
     */
    @Provide
    Arbitrary<ImbalancedDraft> imbalancedDrafts() {
        Arbitrary<List<BigDecimal>> debitAmounts = amounts().list().ofMinSize(1).ofMaxSize(6);
        Arbitrary<List<BigDecimal>> creditAmounts = amounts().list().ofMinSize(1).ofMaxSize(6);

        return Combinators.combine(debitAmounts, creditAmounts).as((debits, credits) -> {
            List<PostingLine> lines = new ArrayList<>();
            long ledgerId = 1L;
            for (BigDecimal amount : debits) {
                lines.add(PostingLine.debit(ledgerId++, AccountNature.ASSET, amount));
            }
            for (BigDecimal amount : credits) {
                lines.add(PostingLine.credit(ledgerId++, AccountNature.INCOME, amount));
            }
            BigDecimal expectedDebit = sum(debits);
            BigDecimal expectedCredit = sum(credits);
            DraftVoucher draft = new DraftVoucher(
                    VoucherType.JOURNAL, LocalDate.of(2025, 4, 1), "Imbalance property test", lines);
            return new ImbalancedDraft(draft, expectedDebit, expectedCredit);
        }).filter(sample -> sample.expectedDebit().compareTo(sample.expectedCredit()) != 0);
    }

    /** Strictly positive money amounts at scale 2 (1 paisa up to 100000.00). */
    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(1L, 10_000_000L).map(cents -> BigDecimal.valueOf(cents, MONEY_SCALE));
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
