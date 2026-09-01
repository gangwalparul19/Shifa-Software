package com.shifa.oms.ledger.statements.domain;

import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.BalanceMath;
import com.shifa.oms.ledger.domain.BalanceMath.SidedBalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * The pure Profit &amp; Loss statement builder for a reporting period (Financial Statements,
 * Reqs 4.1, 4.2, 4.3, 4.4, 4.5, 5.1).
 *
 * <p>Given each INCOME/EXPENSE ledger's <strong>period net movement</strong> as its
 * {@link LedgerBalanceInput#signedBalance() signed amount} (relative to the account nature's normal
 * side per {@link BalanceMath}), {@link #build(List, List)} produces the two sides of the statement
 * as Tally-style grouped {@link StatementSection sections} rolled up by {@link AccountGroupTree}:
 *
 * <ul>
 *   <li>the <strong>income side</strong> — the forest of INCOME-nature groups (Req 4.2);</li>
 *   <li>the <strong>expenses side</strong> — the forest of EXPENSE-nature groups (Req 4.2).</li>
 * </ul>
 *
 * <p>Because INCOME's normal side is credit and EXPENSE's normal side is debit, the section signed
 * totals are naturally credit-positive and debit-positive respectively; they are surfaced as
 * {@link #totalIncome} and {@link #totalExpenses} (Req 4.3). The {@link #netProfit} is
 * {@code totalIncome − totalExpenses} (Req 4.4); when that is negative the result is a
 * <strong>Net Loss</strong> of {@code totalExpenses − totalIncome} surfaced via {@link #netLoss}
 * and {@link #netLossAmount} (Req 4.5).
 *
 * <p>A <strong>gross-profit</strong> subtotal ({@code Σ direct income − Σ direct expenses}) is
 * included <em>only where</em> the Chart of Accounts contains the seeded {@code Direct Income} and/or
 * {@code Direct Expenses} groups (the Req 4 decision-point default); otherwise {@link #grossProfit}
 * is {@code null} and the subtotal is omitted.
 *
 * <p>The {@code netProfit} this produces (credit-positive when a profit) is the exact value a caller
 * feeds to {@code BalanceSheet.build} as the retained-earnings line, so the P&amp;L net profit ties
 * to the Balance Sheet for the same period (Reqs 3.5, 9.1, 13.3). The class is immutable, Spring-free,
 * and computed by a single total, exception-free factory, so its composition invariants are directly
 * property-testable.
 *
 * @param income        the income side (INCOME-nature grouped sections; required)
 * @param expenses      the expenses side (EXPENSE-nature grouped sections; required)
 * @param totalIncome   total income for the period, credit-positive magnitude (required)
 * @param totalExpenses total expenses for the period, debit-positive magnitude (required)
 * @param netProfit     {@code totalIncome − totalExpenses} (required; negative denotes a loss)
 * @param netLoss       whether the period is a net loss ({@code netProfit < 0}, Req 4.5)
 * @param netLossAmount the net loss magnitude ({@code totalExpenses − totalIncome} when a loss, else zero)
 * @param grossProfit   {@code Σ direct income − Σ direct expenses}, or {@code null} when no Direct groups exist
 */
public record ProfitAndLoss(StatementSection income, StatementSection expenses, BigDecimal totalIncome,
                            BigDecimal totalExpenses, BigDecimal netProfit, boolean netLoss,
                            BigDecimal netLossAmount, BigDecimal grossProfit) {

    /** Scale used for money, matching the codebase-wide {@code BigDecimal} scale-2 convention. */
    private static final int MONEY_SCALE = 2;

    /** The seeded account-group name whose subtotal forms the direct-income leg of gross profit. */
    private static final String DIRECT_INCOME_GROUP = "Direct Income";

    /** The seeded account-group name whose subtotal forms the direct-expenses leg of gross profit. */
    private static final String DIRECT_EXPENSES_GROUP = "Direct Expenses";

    public ProfitAndLoss {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(expenses, "expenses");
        Objects.requireNonNull(totalIncome, "totalIncome");
        Objects.requireNonNull(totalExpenses, "totalExpenses");
        Objects.requireNonNull(netProfit, "netProfit");
        Objects.requireNonNull(netLossAmount, "netLossAmount");
        totalIncome = totalIncome.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        totalExpenses = totalExpenses.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        netProfit = netProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        netLossAmount = netLossAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (grossProfit != null) {
            grossProfit = grossProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * Build a Profit &amp; Loss statement from the period net movements of the INCOME/EXPENSE ledger
     * accounts and the Chart-of-Accounts group forest (Reqs 4.1–4.5, 5.1).
     *
     * @param incomeExpenseMovements the INCOME/EXPENSE ledger period movements (must not be
     *                               {@code null}; non-INCOME/EXPENSE and {@code null} elements are
     *                               ignored by the roll-up)
     * @param groups                 the Chart-of-Accounts group nodes (must not be {@code null})
     * @return the computed Profit &amp; Loss statement
     */
    public static ProfitAndLoss build(List<LedgerBalanceInput> incomeExpenseMovements, List<GroupInput> groups) {
        Objects.requireNonNull(incomeExpenseMovements, "incomeExpenseMovements");
        Objects.requireNonNull(groups, "groups");

        List<GroupNode> incomeGroups =
                AccountGroupTree.build(groups, incomeExpenseMovements, EnumSet.of(AccountNature.INCOME));
        List<GroupNode> expenseGroups =
                AccountGroupTree.build(groups, incomeExpenseMovements, EnumSet.of(AccountNature.EXPENSE));

        // Section signed totals: income is credit-positive, expenses debit-positive (Req 4.3).
        BigDecimal totalIncome = AccountGroupTree.signedTotal(incomeGroups);
        BigDecimal totalExpenses = AccountGroupTree.signedTotal(expenseGroups);

        StatementSection income = new StatementSection("Income", incomeGroups, List.of(),
                totalIncome, BalanceMath.closingSide(AccountNature.INCOME, totalIncome));
        StatementSection expenses = new StatementSection("Expenses", expenseGroups, List.of(),
                totalExpenses, BalanceMath.closingSide(AccountNature.EXPENSE, totalExpenses));

        // Net profit = income − expenses; a negative value is presented as a Net Loss (Reqs 4.4, 4.5).
        BigDecimal netProfit = totalIncome.subtract(totalExpenses).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean netLoss = netProfit.signum() < 0;
        BigDecimal netLossAmount = netLoss
                ? netProfit.negate()
                : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal grossProfit = grossProfit(incomeGroups, expenseGroups);

        return new ProfitAndLoss(income, expenses, totalIncome, totalExpenses, netProfit, netLoss,
                netLossAmount, grossProfit);
    }

    /**
     * The gross-profit subtotal {@code Σ direct income − Σ direct expenses}, or {@code null} when the
     * Chart of Accounts contains neither a {@code Direct Income} nor a {@code Direct Expenses} group
     * (Req 4 decision-point default).
     */
    private static BigDecimal grossProfit(List<GroupNode> incomeGroups, List<GroupNode> expenseGroups) {
        GroupNode directIncome = findByName(incomeGroups, DIRECT_INCOME_GROUP);
        GroupNode directExpenses = findByName(expenseGroups, DIRECT_EXPENSES_GROUP);
        if (directIncome == null && directExpenses == null) {
            return null; // no Direct groups → gross profit omitted
        }
        BigDecimal directIncomeSubtotal = directIncome == null ? BigDecimal.ZERO : directIncome.signedSubtotal();
        BigDecimal directExpensesSubtotal =
                directExpenses == null ? BigDecimal.ZERO : directExpenses.signedSubtotal();
        return directIncomeSubtotal.subtract(directExpensesSubtotal).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Depth-first search for a rolled-up group node by (case-insensitive) name within a forest. */
    private static GroupNode findByName(List<GroupNode> nodes, String name) {
        for (GroupNode node : nodes) {
            if (name.equalsIgnoreCase(node.name())) {
                return node;
            }
            GroupNode found = findByName(node.childGroups(), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The income-side section total as a display side + non-negative magnitude. */
    public SidedBalance totalIncomeSide() {
        return income.total();
    }

    /** The expenses-side section total as a display side + non-negative magnitude. */
    public SidedBalance totalExpensesSide() {
        return expenses.total();
    }

    /** Whether a gross-profit subtotal was computed (a Direct Income/Direct Expenses group exists). */
    public boolean hasGrossProfit() {
        return grossProfit != null;
    }
}
