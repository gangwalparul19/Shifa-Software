package com.shifa.oms.ledger.statements.dto;

import com.shifa.oms.ledger.statements.ProfitAndLossResult;
import com.shifa.oms.ledger.statements.domain.ProfitAndLoss;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Read view of the Profit &amp; Loss statement ({@code GET /api/accounting/profit-and-loss},
 * Financial Statements Reqs 4, 5, 7.1, 8, 13.4).
 *
 * <p>Carries the income and expenses sides as recursive {@link StatementNodeResponse} trees
 * (drill-down), the income/expense totals as positive magnitudes, the optional gross-profit subtotal
 * (present only where Direct groups exist, else {@code null}), the net profit and — for convenience —
 * the net-loss flag and amount (Req 4.5), and, when comparative, the prior totals. Money is
 * normalised to scale 2 in {@link #from}; enums serialise as {@code name()} and {@link LocalDate} as
 * {@code yyyy-MM-dd}.
 *
 * @param from             the resolved reporting-period start date
 * @param to               the resolved reporting-period end date
 * @param financialYearId  the FY the period resolved from, or {@code null} for a range
 * @param comparative      whether a comparative prior period was requested (Req 7)
 * @param income           the INCOME-nature top-level groups (drill-down tree)
 * @param totalIncome      the total income magnitude (credit-positive, scale 2)
 * @param expenses         the EXPENSE-nature top-level groups (drill-down tree)
 * @param totalExpenses    the total expenses magnitude (debit-positive, scale 2)
 * @param grossProfit      {@code Σ direct income − Σ direct expenses}, or {@code null} when no Direct groups
 * @param netProfit        {@code totalIncome − totalExpenses}; negative denotes a loss (scale 2)
 * @param netLoss          whether the period is a net loss (Req 4.5)
 * @param netLossAmount    the net-loss magnitude when a loss, else zero (scale 2, Req 4.5)
 * @param priorTotalIncome the prior-period total income, or {@code null} when not comparative
 * @param priorTotalExpenses the prior-period total expenses, or {@code null} when not comparative
 * @param priorNetProfit   the prior-period net profit, or {@code null} when not comparative
 */
public record ProfitAndLossResponse(
        LocalDate from, LocalDate to, Long financialYearId, boolean comparative,
        List<StatementNodeResponse> income, BigDecimal totalIncome,
        List<StatementNodeResponse> expenses, BigDecimal totalExpenses,
        BigDecimal grossProfit, BigDecimal netProfit, boolean netLoss, BigDecimal netLossAmount,
        BigDecimal priorTotalIncome, BigDecimal priorTotalExpenses, BigDecimal priorNetProfit) {

    private static final int MONEY_SCALE = 2;

    /**
     * Maps a {@link ProfitAndLossResult} to its response view (Reqs 4, 5, 7.1, 8), aligning the
     * comparative prior period node-by-node when it was requested.
     *
     * @param result the Profit &amp; Loss service result
     * @return the response view with money at scale 2
     */
    public static ProfitAndLossResponse from(ProfitAndLossResult result) {
        ProfitAndLoss current = result.statement();
        ProfitAndLoss prior = result.comparative() ? result.prior() : null;

        List<StatementNodeResponse> income = StatementNodeResponse.fromList(
                current.income().groups(), prior == null ? null : prior.income().groups());
        List<StatementNodeResponse> expenses = StatementNodeResponse.fromList(
                current.expenses().groups(), prior == null ? null : prior.expenses().groups());

        BigDecimal priorTotalIncome = prior == null ? null : scale(prior.totalIncome());
        BigDecimal priorTotalExpenses = prior == null ? null : scale(prior.totalExpenses());
        BigDecimal priorNetProfit = prior == null ? null : scale(prior.netProfit());

        return new ProfitAndLossResponse(
                result.from(), result.to(), result.financialYearId(), result.comparative(),
                income, scale(current.totalIncome()),
                expenses, scale(current.totalExpenses()),
                scale(current.grossProfit()), scale(current.netProfit()), current.netLoss(),
                scale(current.netLossAmount()),
                priorTotalIncome, priorTotalExpenses, priorNetProfit);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
