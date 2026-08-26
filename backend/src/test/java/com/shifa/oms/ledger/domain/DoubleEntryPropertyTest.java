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
 * Property-based tests for the {@link DoubleEntry} validator (General Ledger, design Correctness
 * Property 8).
 *
 * <p>Feature: general-ledger-accounting, Property 8: Double-entry posting validation.
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 5.4, 5.5, 5.6</b>
 *
 * <p>For any draft voucher (with a present date, type, and narration so Req 5.7 never confounds the
 * structural rules), posting is accepted <em>if and only if</em> it has at least two lines
 * (Req 5.1), every line references exactly one ledger (Req 5.4) and carries a strictly positive
 * amount on exactly one of debit or credit — never both, never neither (Reqs 5.5, 5.6) — and the
 * sum of debit amounts equals the sum of credit amounts (Req 5.2). Any draft violating any of these
 * is rejected.
 */
class DoubleEntryPropertyTest {

    /** Codebase-wide money scale (matches {@link DoubleEntry}). */
    private static final int MONEY_SCALE = 2;

    private static final LocalDate ANY_DATE = LocalDate.of(2025, 5, 1);
    private static final String ANY_NARRATION = "Property test voucher";

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 8: Double-entry posting validation
    // **Validates: Requirements 5.1, 5.2, 5.4, 5.5, 5.6**
    // The comprehensive "if and only if": validate().ok() agrees with an independently computed
    // oracle over the four structural rules, across arbitrary (mostly invalid) drafts.
    // ---------------------------------------------------------------------------------------------

    /**
     * For an arbitrary draft (valid voucher-level fields, but arbitrary lines/ledgers/amounts),
     * acceptance holds exactly when all four structural rules hold, and a rejection always carries
     * at least one violation message.
     */
    @Property(tries = 500)
    void acceptedIffAllStructuralRulesHold(@ForAll("arbitraryDrafts") DraftVoucher draft) {
        boolean expectedOk = atLeastTwoLines(draft)
                && everyLineReferencesALedger(draft)
                && everyLineHasStrictlyPositiveAmount(draft)
                && debitsEqualCredits(draft);

        DoubleEntry.Result result = DoubleEntry.validate(draft);

        assertThat(result.ok())
                .as("validate().ok() must equal the structural-rules oracle for draft %s", draft.lines())
                .isEqualTo(expectedOk);

        if (!expectedOk) {
            assertThat(result.violations())
                    .as("a rejected draft must report at least one violation")
                    .isNotEmpty();
        } else {
            assertThat(result.violations()).isEmpty();
        }
    }

    /** Any draft that satisfies all four rules by construction is always accepted. */
    @Property(tries = 300)
    void wellFormedBalancedVouchersAreAccepted(@ForAll("balancedDrafts") DraftVoucher draft) {
        DoubleEntry.Result result = DoubleEntry.validate(draft);

        assertThat(result.ok()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.balance().balanced()).isTrue();
    }

    /** A single line — however well-formed and "balanced" — is rejected (Req 5.1). */
    @Property(tries = 200)
    void fewerThanTwoLinesIsRejected(@ForAll("positiveAmounts") BigDecimal amount) {
        // One line can never balance (debit-only or credit-only), so also exercise a lone
        // self-balancing-looking single line: still < 2 lines => rejected.
        DraftVoucher oneLine = new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION,
                List.of(PostingLine.debit(1L, AccountNature.ASSET, amount)));
        DraftVoucher empty = new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, List.of());

        assertThat(DoubleEntry.validate(oneLine).ok()).isFalse();
        assertThat(DoubleEntry.validate(empty).ok()).isFalse();
    }

    /** An otherwise-balanced draft with a missing ledger reference on any line is rejected (Req 5.4). */
    @Property(tries = 300)
    void missingLedgerReferenceIsRejected(@ForAll("positiveAmounts") BigDecimal amount) {
        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(null, AccountNature.ASSET, DrCr.DEBIT, amount)); // no ledger ref
        lines.add(PostingLine.credit(2L, AccountNature.INCOME, amount));
        DraftVoucher draft = new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, lines);

        DoubleEntry.Result result = DoubleEntry.validate(draft);

        assertThat(result.ok()).isFalse();
        // The balance itself is fine; the rejection is purely the missing reference.
        assertThat(result.balance().balanced()).isTrue();
    }

    /** An otherwise-balanced draft with a non-positive or absent amount on any line is rejected (Reqs 5.6, 5.5). */
    @Property(tries = 300)
    void nonPositiveOrAbsentAmountIsRejected(@ForAll("nonPositiveOrNullAmounts") BigDecimal badAmount) {
        // Pair a bad-amount debit line with a positive credit line. The bad line carries "neither"
        // a valid debit nor credit amount, so the draft must be rejected regardless of totals.
        List<PostingLine> lines = new ArrayList<>();
        lines.add(new PostingLine(1L, AccountNature.ASSET, DrCr.DEBIT, badAmount));
        lines.add(PostingLine.credit(2L, AccountNature.INCOME, new BigDecimal("100.00")));
        DraftVoucher draft = new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, lines);

        assertThat(DoubleEntry.validate(draft).ok()).isFalse();
    }

    /** A draft whose debit total differs from its credit total is rejected (Req 5.2). */
    @Property(tries = 300)
    void imbalancedTotalsAreRejected(@ForAll("imbalancedDrafts") DraftVoucher draft) {
        DoubleEntry.Result result = DoubleEntry.validate(draft);

        assertThat(result.balance().balanced()).isFalse();
        assertThat(result.ok()).isFalse();
    }

    // --- Oracle (independent re-implementation of the four structural rules) ----------------------

    private static boolean atLeastTwoLines(DraftVoucher draft) {
        return draft.lines().size() >= 2;
    }

    private static boolean everyLineReferencesALedger(DraftVoucher draft) {
        return draft.lines().stream().allMatch(line -> line != null && line.ledgerId() != null);
    }

    private static boolean everyLineHasStrictlyPositiveAmount(DraftVoucher draft) {
        return draft.lines().stream()
                .allMatch(line -> line != null && line.amount() != null && line.amount().signum() > 0);
    }

    private static boolean debitsEqualCredits(DraftVoucher draft) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (PostingLine line : draft.lines()) {
            if (line == null) {
                continue;
            }
            debit = debit.add(line.debitAmount());
            credit = credit.add(line.creditAmount());
        }
        return debit.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .compareTo(credit.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0;
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Strictly positive, scale-2 money amounts. */
    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
    }

    /** Amounts that are never a valid line amount: zero, negative, or {@code null}. */
    @Provide
    Arbitrary<BigDecimal> nonPositiveOrNullAmounts() {
        Arbitrary<BigDecimal> zero = Arbitraries.of(BigDecimal.ZERO, new BigDecimal("0.00"));
        Arbitrary<BigDecimal> negative =
                Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(-paise, 2));
        return Arbitraries.oneOf(zero, negative).injectNull(0.34);
    }

    /**
     * Arbitrary drafts with valid voucher-level fields but arbitrary lines: line counts 0..6, each
     * line with a possibly-null ledger reference, a random side, and a positive / zero / negative /
     * null amount. This deliberately produces mostly-invalid drafts so the iff is exercised on both
     * sides.
     */
    @Provide
    Arbitrary<DraftVoucher> arbitraryDrafts() {
        Arbitrary<Long> ledgerIds = Arbitraries.longs().between(1L, 10_000L).injectNull(0.2);
        Arbitrary<AccountNature> natures = Arbitraries.of(AccountNature.values());
        Arbitrary<DrCr> sides = Arbitraries.of(DrCr.values());
        Arbitrary<BigDecimal> amounts = Arbitraries.oneOf(
                positiveAmounts(),
                Arbitraries.of(BigDecimal.ZERO),
                Arbitraries.longs().between(1L, 100_000_000L).map(p -> BigDecimal.valueOf(-p, 2))
        ).injectNull(0.15);

        Arbitrary<PostingLine> lines = Combinators.combine(ledgerIds, natures, sides, amounts)
                .as(PostingLine::new);

        return lines.list().ofMinSize(0).ofMaxSize(6)
                .map(ls -> new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, ls));
    }

    /**
     * Well-formed, guaranteed-balanced drafts: for each of 1..4 positive amounts, a matched
     * debit + credit line of that amount, so there are always >= 2 lines, every line references a
     * ledger, every amount is strictly positive, and debits equal credits.
     */
    @Provide
    Arbitrary<DraftVoucher> balancedDrafts() {
        return positiveAmounts().list().ofMinSize(1).ofMaxSize(4).map(amounts -> {
            List<PostingLine> lines = new ArrayList<>();
            long id = 1L;
            for (BigDecimal amount : amounts) {
                lines.add(PostingLine.debit(id++, AccountNature.ASSET, amount));
                lines.add(PostingLine.credit(id++, AccountNature.INCOME, amount));
            }
            return new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, lines);
        });
    }

    /**
     * Well-formed drafts (>= 2 lines, ledger refs, positive amounts) whose debit total differs from
     * the credit total by a strictly positive delta, so the only violated rule is Req 5.2.
     */
    @Provide
    Arbitrary<DraftVoucher> imbalancedDrafts() {
        return Combinators.combine(positiveAmounts(), positiveAmounts())
                .as((debit, delta) -> {
                    BigDecimal credit = debit.add(delta); // credit strictly greater => imbalance
                    List<PostingLine> lines = new ArrayList<>();
                    lines.add(PostingLine.debit(1L, AccountNature.ASSET, debit));
                    lines.add(PostingLine.credit(2L, AccountNature.INCOME, credit));
                    return new DraftVoucher(VoucherType.JOURNAL, ANY_DATE, ANY_NARRATION, lines);
                });
    }
}
