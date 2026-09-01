package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.statements.domain.ProfitAndLoss;

import java.time.LocalDate;

/**
 * The result of computing a Profit &amp; Loss statement for a resolved reporting period (Financial
 * Statements, Reqs 1.1, 1.2, 4.1&ndash;4.5, 7.1).
 *
 * <p>It carries the pure {@link ProfitAndLoss} statement (the current period) together with the
 * period metadata the REST/DTO layer needs, and&mdash;when {@link #comparative} is
 * {@code true}&mdash;the pure {@link ProfitAndLoss} for the immediately preceding equal-length window
 * (the prior Financial Year for an FY request; the equal-length window ending the day before
 * {@code from} for a date range, Req 7.1). When {@link #comparative} is {@code false} the
 * {@link #prior} statement is {@code null} (Req 7.2).
 *
 * <p>This is the shape the DTO mapper (task 7.1) and the {@code FinancialStatementsController}
 * (task 8.1) consume: {@code from}/{@code to}/{@code financialYearId} identify the resolved period;
 * {@code statement.income()}/{@code expenses()} carry the Tally-style grouped section trees (with
 * drill-down); {@code statement.totalIncome()}/{@code totalExpenses()}/{@code netProfit()}/
 * {@code netLoss()}/{@code netLossAmount()}/{@code grossProfit()} carry the totals; and {@code prior}
 * carries the same shape for the comparative column when requested.
 *
 * @param statement       the current-period Profit &amp; Loss statement (never {@code null})
 * @param from            the inclusive start date of the resolved reporting period
 * @param to              the inclusive end date of the resolved reporting period
 * @param financialYearId the financial year the period was resolved from, or {@code null} for a range
 * @param comparative     whether a comparative prior period was requested (Req 7.1, 7.2)
 * @param prior           the immediately preceding equal-length period's statement, or {@code null}
 *                        when {@code comparative} is {@code false}
 */
public record ProfitAndLossResult(ProfitAndLoss statement, LocalDate from, LocalDate to,
                                  Long financialYearId, boolean comparative, ProfitAndLoss prior) {
}
