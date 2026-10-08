package com.shifa.oms.ledger.statements;

import com.shifa.oms.ledger.statements.domain.BalanceSheet;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The result of computing a Balance Sheet for a resolved reporting period (Financial Statements,
 * Reqs 1.1, 1.2, 1.5, 2, 3, 7.1).
 *
 * <p>This is the public return type of {@link BalanceSheetService#balanceSheet(Long, LocalDate,
 * LocalDate, boolean)} and the input the response-DTO mapper (task 7.1) and the controller (task
 * 8.1) consume. It carries:
 * <ul>
 *   <li>the pure, compliance-critical {@link BalanceSheet} for the current period — the assets side,
 *       the liabilities-and-equity side (including the injected retained-earnings line), and the
 *       balancing difference;</li>
 *   <li>the period metadata: the {@link #asAtDate} (the resolved period's to-date, Req 1.5), the
 *       resolved {@link #from} / {@link #to} window, the {@link #financialYearId} the period was
 *       resolved from (or {@code null} for a raw date range), and the {@link #comparative} flag;</li>
 *   <li>when {@link #comparative} is requested, the {@link #priorBalanceSheet} computed over the
 *       immediately preceding equal-length window (Req 7.1) — otherwise {@code null}.</li>
 * </ul>
 *
 * <p>Prior figures are kept accessible <em>node-by-node</em> by carrying the whole prior
 * {@link BalanceSheet}: the DTO mapper aligns the current and prior grouped nodes by {@code groupId}
 * (and the retained-earnings line by its synthetic id) so each current figure can be presented next
 * to its prior-period counterpart. When {@link #comparative} is {@code false} the prior figures are
 * simply absent (Req 7.2).
 *
 * @param asAtDate          the As_At_Date of the Balance Sheet — the resolved period's to-date (Req 1.5)
 * @param from              the resolved reporting-period start date
 * @param to                the resolved reporting-period end date (equals {@link #asAtDate})
 * @param financialYearId   the financial year the period was resolved from, or {@code null} for a raw
 *                          date range
 * @param comparative       whether a comparative prior period was requested (Req 7)
 * @param balanceSheet      the current-period Balance Sheet (required)
 * @param priorBalanceSheet the prior-period Balance Sheet over the immediately preceding equal-length
 *                          window, or {@code null} when {@link #comparative} is {@code false}
 */
public record BalanceSheetResult(LocalDate asAtDate, LocalDate from, LocalDate to, Long financialYearId,
                                 boolean comparative, BalanceSheet balanceSheet,
                                 BalanceSheet priorBalanceSheet) {

    public BalanceSheetResult {
        Objects.requireNonNull(asAtDate, "asAtDate");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(balanceSheet, "balanceSheet");
    }
}
