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

import com.shifa.oms.ledger.domain.TrialBalance.AccountActivity;
import com.shifa.oms.ledger.domain.TrialBalance.Row;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the pure {@link TrialBalance} aggregation (General Ledger, design
 * Correctness Property 15).
 *
 * <p>Feature: general-ledger-accounting, Property 15: The Trial Balance balances and reports its
 * difference.
 *
 * <p><b>Validates: Requirements 3.3, 14.1, 14.2, 14.3, 14.4, 18.2</b>
 *
 * <p>For any set of ledger accounts with opening balances and posted voucher lines in a period, the
 * Trial Balance includes exactly those accounts having an opening balance or a line in the period
 * (Req 14.1), each with a closing debit/credit balance consistent with its nature (via
 * {@link BalanceMath#closingSide}); the debit total and credit total each equal the sum of the
 * respective closing magnitudes (Req 14.2); the reported difference equals
 * {@code debitTotal - creditTotal} (Req 14.4); and whenever every voucher and all opening balances
 * in the period are balanced, the debit total equals the credit total (Reqs 14.3, 18.2, 3.3).
 */
class TrialBalancePropertyTest {

    /** Codebase-wide money scale (matches {@link TrialBalance}). */
    private static final int MONEY_SCALE = 2;

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 15: The Trial Balance balances and reports its
    // difference
    // **Validates: Requirements 3.3, 14.1, 14.2, 14.3, 14.4, 18.2**
    // ---------------------------------------------------------------------------------------------

    /**
     * (Req 14.1) The Trial Balance includes exactly the accounts that have an opening balance or a
     * period line — in the supplied order — and drops every account with neither.
     */
    @Property(tries = 300)
    void includesExactlyAccountsWithActivity(@ForAll("arbitraryActivities") List<AccountActivity> activities) {
        TrialBalance tb = TrialBalance.compute(activities);

        List<Long> expectedIds = activities.stream()
                .filter(AccountActivity::isIncluded)
                .map(AccountActivity::ledgerId)
                .toList();
        List<Long> actualIds = tb.rows().stream().map(Row::ledgerId).toList();

        assertThat(actualIds).isEqualTo(expectedIds);
    }

    /**
     * (Reqs 14.1, 12.2) Each included account's row carries the closing side and magnitude that
     * {@link BalanceMath#closingSide} resolves from its {@code opening + net movement} signed
     * balance — i.e. a debit/credit balance consistent with its nature.
     */
    @Property(tries = 300)
    void eachRowMatchesClosingSideOfNature(@ForAll("arbitraryActivities") List<AccountActivity> activities) {
        TrialBalance tb = TrialBalance.compute(activities);

        List<AccountActivity> included = activities.stream().filter(AccountActivity::isIncluded).toList();
        assertThat(tb.rows()).hasSameSizeAs(included);

        for (int i = 0; i < included.size(); i++) {
            AccountActivity activity = included.get(i);
            Row row = tb.rows().get(i);

            BigDecimal closingSigned = activity.openingSigned().add(activity.netMovementSigned());
            BalanceMath.SidedBalance expected = BalanceMath.closingSide(activity.nature(), closingSigned);

            assertThat(row.nature()).isEqualTo(activity.nature());
            assertThat(row.side()).isEqualTo(expected.side());
            assertThat(row.magnitude())
                    .as("closing magnitude for ledger %s", activity.ledgerId())
                    .isEqualByComparingTo(expected.magnitude().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            // Exactly one side carries the magnitude; the other is zero.
            assertThat(row.debitBalance().add(row.creditBalance())).isEqualByComparingTo(row.magnitude());
        }
    }

    /**
     * (Reqs 14.2, 14.4) The debit total equals the sum of every row's closing debit magnitude, the
     * credit total equals the sum of every row's closing credit magnitude, and the reported
     * difference is exactly {@code debitTotal - creditTotal}.
     */
    @Property(tries = 300)
    void totalsAreSumsOfMagnitudesAndDifferenceIsTheirDelta(
            @ForAll("arbitraryActivities") List<AccountActivity> activities) {
        TrialBalance tb = TrialBalance.compute(activities);

        BigDecimal expectedDebit = BigDecimal.ZERO;
        BigDecimal expectedCredit = BigDecimal.ZERO;
        for (Row row : tb.rows()) {
            expectedDebit = expectedDebit.add(row.debitBalance());
            expectedCredit = expectedCredit.add(row.creditBalance());
        }

        assertThat(tb.debitTotal()).isEqualByComparingTo(expectedDebit);
        assertThat(tb.creditTotal()).isEqualByComparingTo(expectedCredit);
        assertThat(tb.difference()).isEqualByComparingTo(tb.debitTotal().subtract(tb.creditTotal()));
    }

    /**
     * (Reqs 14.3, 18.2, 3.3) When every voucher and every opening balance in the period is balanced
     * (equal raw debits and credits), the debit total equals the credit total, the difference is
     * zero, and {@link TrialBalance#balanced()} is {@code true}.
     */
    @Property(tries = 300)
    void balancedWhenAllVouchersAndOpeningsBalance(
            @ForAll("balancedActivities") List<AccountActivity> activities) {
        TrialBalance tb = TrialBalance.compute(activities);

        assertThat(tb.balanced()).isTrue();
        assertThat(tb.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tb.debitTotal()).isEqualByComparingTo(tb.creditTotal());
    }

    // --- Generators ------------------------------------------------------------------------------

    /** Strictly positive, scale-2 money amounts. */
    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
    }

    /** Signed scale-2 balances: strictly positive, strictly negative, or exactly zero. */
    @Provide
    Arbitrary<BigDecimal> signedBalances() {
        Arbitrary<BigDecimal> positive =
                Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(paise, 2));
        Arbitrary<BigDecimal> negative =
                Arbitraries.longs().between(1L, 100_000_000L).map(paise -> BigDecimal.valueOf(-paise, 2));
        Arbitrary<BigDecimal> zero = Arbitraries.of(BigDecimal.ZERO, new BigDecimal("0.00"));
        return Arbitraries.oneOf(positive, negative, zero);
    }

    /**
     * Arbitrary per-account activity: 0..10 accounts (distinct ids assigned by position), each with
     * an arbitrary nature, arbitrary signed opening and movement, and arbitrary inclusion flags — so
     * both included and excluded accounts (and sign flips at closing) are exercised.
     */
    @Provide
    Arbitrary<List<AccountActivity>> arbitraryActivities() {
        Arbitrary<ActivitySpec> spec = Combinators.combine(
                        Arbitraries.of(AccountNature.values()),
                        signedBalances(),
                        signedBalances(),
                        Arbitraries.of(true, false),
                        Arbitraries.of(true, false))
                .as(ActivitySpec::new);

        return spec.list().ofMinSize(0).ofMaxSize(10).map(specs -> {
            List<AccountActivity> activities = new ArrayList<>();
            long id = 1L;
            for (ActivitySpec s : specs) {
                activities.add(new AccountActivity(id++, s.nature, s.opening, s.movement,
                        s.hasOpeningBalance, s.hasPeriodLine));
            }
            return activities;
        });
    }

    /**
     * A guaranteed-balanced set of account activities: 1..8 accounts, with the openings and the
     * period lines both generated as balanced (debit-account, credit-account, amount) postings, so
     * the raw debits equal the raw credits across all accounts by construction. Each account's
     * opening/movement signed balance is derived (relative to its normal side) from its accumulated
     * raw debit/credit, and its inclusion flags reflect whether it took part in any opening/line
     * posting.
     */
    @Provide
    Arbitrary<List<AccountActivity>> balancedActivities() {
        Arbitrary<List<AccountNature>> natureLists =
                Arbitraries.of(AccountNature.values()).list().ofMinSize(1).ofMaxSize(8);

        return natureLists.flatMap(natures -> {
            int n = natures.size();
            Arbitrary<Posting> posting = Combinators.combine(
                            Arbitraries.integers().between(0, n - 1),
                            Arbitraries.integers().between(0, n - 1),
                            positiveAmounts())
                    .as(Posting::new);
            Arbitrary<List<Posting>> openings = posting.list().ofMinSize(0).ofMaxSize(10);
            Arbitrary<List<Posting>> lines = posting.list().ofMinSize(0).ofMaxSize(10);
            return Combinators.combine(openings, lines).as((op, ln) -> buildBalanced(natures, op, ln));
        });
    }

    /**
     * Fold balanced opening and line postings into per-account {@link AccountActivity}s. Every
     * posting contributes its amount equally to a debit account and a credit account, so the total
     * raw debits equal the total raw credits — the Trial Balance must therefore balance.
     */
    private static List<AccountActivity> buildBalanced(List<AccountNature> natures,
                                                       List<Posting> openings, List<Posting> lines) {
        int n = natures.size();
        BigDecimal[] openDr = filledWithZero(n);
        BigDecimal[] openCr = filledWithZero(n);
        BigDecimal[] lineDr = filledWithZero(n);
        BigDecimal[] lineCr = filledWithZero(n);

        for (Posting p : openings) {
            openDr[p.debitIndex] = openDr[p.debitIndex].add(p.amount);
            openCr[p.creditIndex] = openCr[p.creditIndex].add(p.amount);
        }
        for (Posting p : lines) {
            lineDr[p.debitIndex] = lineDr[p.debitIndex].add(p.amount);
            lineCr[p.creditIndex] = lineCr[p.creditIndex].add(p.amount);
        }

        List<AccountActivity> activities = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AccountNature nature = natures.get(i);
            int sign = nature.normalSide() == DrCr.DEBIT ? 1 : -1;
            BigDecimal openingSigned = BigDecimal.valueOf(sign).multiply(openDr[i].subtract(openCr[i]));
            BigDecimal movementSigned = BigDecimal.valueOf(sign).multiply(lineDr[i].subtract(lineCr[i]));
            boolean hasOpening = openDr[i].signum() != 0 || openCr[i].signum() != 0;
            boolean hasLine = lineDr[i].signum() != 0 || lineCr[i].signum() != 0;
            activities.add(new AccountActivity(i + 1L, nature, openingSigned, movementSigned, hasOpening, hasLine));
        }
        return activities;
    }

    private static BigDecimal[] filledWithZero(int n) {
        BigDecimal[] arr = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            arr[i] = BigDecimal.ZERO;
        }
        return arr;
    }

    /** A single arbitrary account's activity spec (id is assigned by position by the generator). */
    private record ActivitySpec(AccountNature nature, BigDecimal opening, BigDecimal movement,
                                boolean hasOpeningBalance, boolean hasPeriodLine) {
    }

    /** A balanced posting: {@code amount} debited to {@code debitIndex} and credited to {@code creditIndex}. */
    private record Posting(int debitIndex, int creditIndex, BigDecimal amount) {
    }
}
