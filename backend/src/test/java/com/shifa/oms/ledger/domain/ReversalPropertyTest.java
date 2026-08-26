package com.shifa.oms.ledger.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link Reversal} line-by-line negation (General Ledger, design Correctness
 * Property 12).
 *
 * <p>Feature: general-ledger-accounting, Property 12: Reversal negates line-by-line and nets to zero.
 *
 * <p><b>Validates: Requirements 6.3</b>
 *
 * <p>For any posted voucher, its reversing voucher contains a negated counterpart of each line (every
 * {@link DrCr#DEBIT DEBIT} becomes a {@link DrCr#CREDIT CREDIT} of the same amount and vice versa),
 * the reversing voucher is itself balanced, and the combined net signed movement of the original plus
 * its reversal is zero for every ledger account involved.
 */
class ReversalPropertyTest {

    private static final LocalDate ANY_DATE = LocalDate.of(2025, 5, 1);
    private static final String ANY_NARRATION = "Property test voucher";

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 12: Reversal negates line-by-line and nets to zero
    // **Validates: Requirements 6.3**
    // ---------------------------------------------------------------------------------------------

    /**
     * {@link Reversal#negateLine(PostingLine)} flips the side while preserving the ledger reference,
     * nature, and amount: a debit of {@code amount} becomes a credit of {@code amount} and vice versa.
     */
    @Property(tries = 200)
    void negateLineFlipsSideAndPreservesEverythingElse(@ForAll("postingLines") PostingLine original) {
        PostingLine negated = Reversal.negateLine(original);

        assertThat(negated.drcr())
                .as("the side must flip")
                .isEqualTo(original.drcr().opposite());
        assertThat(negated.drcr()).isNotEqualTo(original.drcr());
        assertThat(negated.ledgerId()).isEqualTo(original.ledgerId());
        assertThat(negated.nature()).isEqualTo(original.nature());
        assertThat(negated.amount()).isEqualTo(original.amount());
        // Negating twice returns to the original side.
        assertThat(Reversal.negateLine(negated).drcr()).isEqualTo(original.drcr());
    }

    /**
     * {@link Reversal#negate(List)} produces, in the same order, the negated counterpart of every
     * line: same size, and each element is the opposite side / same ledger / nature / amount.
     */
    @Property(tries = 300)
    void negateListIsLineByLineCounterpartInOrder(@ForAll("postedVoucherLines") List<PostingLine> lines) {
        List<PostingLine> reversed = Reversal.negate(lines);

        assertThat(reversed).hasSameSizeAs(lines);
        for (int i = 0; i < lines.size(); i++) {
            PostingLine orig = lines.get(i);
            PostingLine rev = reversed.get(i);
            assertThat(rev.drcr()).isEqualTo(orig.drcr().opposite());
            assertThat(rev.ledgerId()).isEqualTo(orig.ledgerId());
            assertThat(rev.nature()).isEqualTo(orig.nature());
            assertThat(rev.amount()).isEqualTo(orig.amount());
        }
    }

    /**
     * When the original voucher is balanced (debits == credits, as every posted voucher must be), the
     * reversing set is itself balanced — negation merely swaps the debit and credit totals.
     */
    @Property(tries = 300)
    void reversingSetIsItselfBalanced(@ForAll("postedVoucherLines") List<PostingLine> lines) {
        BigDecimal originalDebits = totalDebits(lines);
        BigDecimal originalCredits = totalCredits(lines);
        // Precondition: the generator yields balanced posted vouchers.
        assertThat(originalDebits).isEqualByComparingTo(originalCredits);

        List<PostingLine> reversed = Reversal.negate(lines);

        // The reversing set's totals are the original's, swapped — and therefore still equal.
        assertThat(totalDebits(reversed)).isEqualByComparingTo(originalCredits);
        assertThat(totalCredits(reversed)).isEqualByComparingTo(originalDebits);
        assertThat(totalDebits(reversed)).isEqualByComparingTo(totalCredits(reversed));
    }

    /**
     * The combined net signed movement of the original plus its reversal is zero for every ledger
     * account involved: each reversal line negates its original line's signed delta, so per-ledger
     * sums cancel exactly.
     */
    @Property(tries = 300)
    void originalPlusReversalNetsToZeroPerLedger(@ForAll("postedVoucherLines") List<PostingLine> lines) {
        List<PostingLine> reversed = Reversal.negate(lines);

        Map<Long, BigDecimal> netByLedger = new HashMap<>();
        accumulate(netByLedger, lines);
        accumulate(netByLedger, reversed);

        assertThat(netByLedger).isNotEmpty();
        netByLedger.forEach((ledgerId, net) ->
                assertThat(net)
                        .as("net signed movement for ledger %d must be zero", ledgerId)
                        .isEqualByComparingTo(BigDecimal.ZERO));
    }

    /**
     * {@link Reversal#negate(DraftVoucher)} reuses the original's type and date, carries the negated
     * lines, has a non-blank narration, and is itself a valid, balanced double entry
     * ({@link DoubleEntry} accepts it).
     */
    @Property(tries = 300)
    void reversingDraftReusesHeaderAndIsValidBalancedDoubleEntry(
            @ForAll("postedVoucherLines") List<PostingLine> lines) {
        DraftVoucher original = new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, lines);

        DraftVoucher reversing = Reversal.negate(original);

        assertThat(reversing.type()).isEqualTo(original.type());
        assertThat(reversing.date()).isEqualTo(original.date());
        assertThat(reversing.narration()).isNotBlank();
        assertThat(reversing.lines()).isEqualTo(Reversal.negate(lines));

        DoubleEntry.Result result = DoubleEntry.validate(reversing);
        assertThat(result.ok())
                .as("the reversing draft of a valid posted voucher must itself be a valid double entry")
                .isTrue();
        assertThat(result.balance().balanced()).isTrue();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static BigDecimal totalDebits(List<PostingLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (PostingLine line : lines) {
            total = total.add(line.debitAmount());
        }
        return total;
    }

    private static BigDecimal totalCredits(List<PostingLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (PostingLine line : lines) {
            total = total.add(line.creditAmount());
        }
        return total;
    }

    private static void accumulate(Map<Long, BigDecimal> netByLedger, List<PostingLine> lines) {
        for (PostingLine line : lines) {
            BigDecimal delta = BalanceMath.signedDelta(line.nature(), line.drcr(), line.amount());
            netByLedger.merge(line.ledgerId(), delta, BigDecimal::add);
        }
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Strictly positive, scale-2 money amounts. */
    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
    }

    /** Arbitrary well-formed single posting lines (ledger ref, nature, side, positive amount). */
    @Provide
    Arbitrary<PostingLine> postingLines() {
        Arbitrary<Long> ledgerIds = Arbitraries.longs().between(1L, 10_000L);
        Arbitrary<AccountNature> natures = Arbitraries.of(AccountNature.values());
        Arbitrary<DrCr> sides = Arbitraries.of(DrCr.values());
        return Combinators.combine(ledgerIds, natures, sides, positiveAmounts()).as(PostingLine::new);
    }

    /**
     * Balanced posted-voucher line sets: for each of 1..4 positive amounts a matched debit + credit
     * of that amount, so there are always >= 2 lines, every line references a distinct ledger, every
     * amount is strictly positive, and debits equal credits (a valid posted voucher per
     * {@link DoubleEntry}). Natures are varied across the five so the sign convention is exercised.
     */
    @Provide
    Arbitrary<List<PostingLine>> postedVoucherLines() {
        AccountNature[] natures = AccountNature.values();
        return positiveAmounts().list().ofMinSize(1).ofMaxSize(4).map(amounts -> {
            List<PostingLine> lines = new ArrayList<>();
            long id = 1L;
            int n = 0;
            for (BigDecimal amount : amounts) {
                AccountNature debitNature = natures[n++ % natures.length];
                AccountNature creditNature = natures[n++ % natures.length];
                lines.add(PostingLine.debit(id++, debitNature, amount));
                lines.add(PostingLine.credit(id++, creditNature, amount));
            }
            return lines;
        });
    }
}
