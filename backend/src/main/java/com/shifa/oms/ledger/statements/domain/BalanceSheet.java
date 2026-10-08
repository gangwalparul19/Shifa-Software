package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The pure Balance Sheet builder for the financial statements (Financial Statements, Reqs 2, 3, 5,
 * 9.1, 13.2).
 *
 * <p>Given the closing signed balances of the ledger accounts as at the As_At_Date
 * ({@link LedgerBalanceInput}s), the Chart-of-Accounts group forest ({@link GroupInput}s), and the
 * period's Net_Profit (credit-positive, {@code netProfitSigned}), {@link #build(List, List,
 * BigDecimal)} composes the two sides of the Balance Sheet as a pure re-aggregation of ledger
 * balances — no Spring, no JPA, scale-2 {@link BigDecimal} money.
 *
 * <ul>
 *   <li><strong>Assets side</strong> — the roll-up of the {@link AccountNature#ASSET ASSET}-nature
 *       group forest via {@link AccountGroupTree}; the section total is the sum of its top-level
 *       group subtotals (debit-positive, Reqs 2.3, 2.5, 5.4).</li>
 *   <li><strong>Liabilities-and-equity side</strong> — the roll-up of the
 *       {@link AccountNature#LIABILITY LIABILITY}- and {@link AccountNature#EQUITY EQUITY}-nature
 *       group forest, <em>plus</em> a synthetic retained-earnings {@link StatementSection#extraLines
 *       extra line} carrying {@code netProfitSigned} (credit-positive) — the single current-period
 *       retained line (Reqs 3.1, 3.2). The section total is the sum of its group subtotals plus the
 *       retained-earnings line (credit-positive).</li>
 * </ul>
 *
 * <p>{@link AccountNature#INCOME INCOME}- and {@link AccountNature#EXPENSE EXPENSE}-nature ledgers
 * are never placed on either side; their net effect is recognised only through the injected
 * retained-earnings line (Req 2.4) — {@link AccountGroupTree#build} attaches only the requested
 * natures, so they are excluded by construction.
 *
 * <p>{@link #difference()} is {@code assetsTotalSigned − liabilitiesAndEquityTotalSigned} (Req 3.4)
 * and {@link #balanced()} is true when it is zero. Because {@code netProfitSigned = Σ income − Σ
 * expense} and, when the period's Trial Balance balances, {@code Σ asset = Σ liability + Σ equity +
 * (Σ income − Σ expense)} (the debit = credit identity), the two side totals are equal — the Balance
 * Sheet balances by construction (Reqs 3.3, 13.2) and reports the exact non-zero difference otherwise
 * (Reqs 3.4, 9.4).
 *
 * @param assets               the assets side (ASSET-nature top-level groups + section total)
 * @param liabilitiesAndEquity the liabilities-and-equity side (LIABILITY + EQUITY top-level groups,
 *                             the retained-earnings extra line, and the section total)
 * @param retainedEarnings     the synthetic retained-earnings (Net_Profit) line injected into equity
 * @param difference           {@code assetsTotalSigned − liabilitiesAndEquityTotalSigned} at scale 2
 */
public record BalanceSheet(StatementSection assets, StatementSection liabilitiesAndEquity,
                           LedgerLine retainedEarnings, BigDecimal difference) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    /** Synthetic ledger id for the injected retained-earnings line (not a real ledger account). */
    public static final long RETAINED_EARNINGS_LEDGER_ID = 0L;

    /** The Tally-style label for the injected current-period net-profit line within equity. */
    public static final String RETAINED_EARNINGS_LABEL = "Profit & Loss A/c";

    public BalanceSheet {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(liabilitiesAndEquity, "liabilitiesAndEquity");
        Objects.requireNonNull(retainedEarnings, "retainedEarnings");
        Objects.requireNonNull(difference, "difference");
        difference = difference.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Compose a Balance Sheet as at a date from ledger closing balances, the account-group forest,
     * and the period's Net_Profit (Reqs 2.1–2.5, 3.2, 3.4, 5.1, 5.4).
     *
     * @param ledgers         the ledger closing balances as at the As_At_Date (must not be
     *                        {@code null}); INCOME/EXPENSE entries are ignored for both sides
     * @param groups          the Chart-of-Accounts group nodes (must not be {@code null})
     * @param netProfitSigned the period's Net_Profit, credit-positive (must not be {@code null});
     *                        a negative value denotes a Net_Loss
     * @return the composed Balance Sheet with both sides, the retained-earnings line, and the
     *         balancing difference
     */
    public static BalanceSheet build(List<LedgerBalanceInput> ledgers, List<GroupInput> groups,
                                     BigDecimal netProfitSigned) {
        Objects.requireNonNull(ledgers, "ledgers");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(netProfitSigned, "netProfitSigned");

        BigDecimal netProfit = netProfitSigned.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // Assets side: ASSET-nature group forest (debit-positive). INCOME/EXPENSE excluded by build.
        List<GroupNode> assetGroups = AccountGroupTree.build(groups, ledgers, Set.of(AccountNature.ASSET));
        BigDecimal assetsSigned = AccountGroupTree.signedTotal(assetGroups);
        StatementSection assets = new StatementSection("Assets", assetGroups, List.of(),
                assetsSigned, BalanceMath.closingSide(AccountNature.ASSET, assetsSigned));

        // Liabilities-and-equity side: LIABILITY + EQUITY group forest (both credit-normal) plus the
        // synthetic retained-earnings line carrying the period's net profit (credit-positive).
        List<GroupNode> liabilitiesEquityGroups = AccountGroupTree.build(groups, ledgers,
                Set.of(AccountNature.LIABILITY, AccountNature.EQUITY));
        BigDecimal liabilitiesEquityGroupsSigned = AccountGroupTree.signedTotal(liabilitiesEquityGroups);

        SidedBalance retainedEarningsBalance = BalanceMath.closingSide(AccountNature.EQUITY, netProfit);
        LedgerLine retainedEarnings = new LedgerLine(RETAINED_EARNINGS_LEDGER_ID, RETAINED_EARNINGS_LABEL,
                netProfit, retainedEarningsBalance);

        BigDecimal liabilitiesEquitySigned = liabilitiesEquityGroupsSigned.add(netProfit)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        StatementSection liabilitiesAndEquity = new StatementSection("Liabilities and Equity",
                liabilitiesEquityGroups, List.of(retainedEarnings), liabilitiesEquitySigned,
                BalanceMath.closingSide(AccountNature.EQUITY, liabilitiesEquitySigned));

        BigDecimal difference = assetsSigned.subtract(liabilitiesEquitySigned)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new BalanceSheet(assets, liabilitiesAndEquity, retainedEarnings, difference);
    }

    /**
     * Whether the Balance Sheet balances — the assets-side total equals the
     * liabilities-and-equity-side total (including the retained-earnings line), i.e. the
     * {@link #difference()} is zero (Reqs 3.3, 13.2).
     */
    public boolean balanced() {
        return difference.signum() == 0;
    }
}
