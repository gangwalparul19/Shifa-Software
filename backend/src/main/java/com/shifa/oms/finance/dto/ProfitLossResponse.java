package com.shifa.oms.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The profit &amp; loss summary for a date window ({@code GET
 * /api/admin/finance/pnl}, Feature C3).
 *
 * <p><strong>Definitions (documented, derived from existing data — no schema
 * invented):</strong>
 * <ul>
 *   <li>{@code revenue} — sum of {@code orders.total_amount} for orders
 *       <em>created</em> within the window, excluding REJECTED and CANCELLED
 *       orders (they never produced sellable revenue).</li>
 *   <li>{@code courierCost} — the courier-attributable P&amp;L cost. The schema
 *       does not model per-shipment courier charges, so this is derived from the
 *       reconciliation receivables as the value of loss/damage claims that are
 *       <em>not yet recovered</em> from the courier for shipments in the window
 *       (i.e. {@code claimsOutstanding}). This is the closest courier-attributable
 *       cost available and represents inventory value lost in transit that the
 *       courier still owes.</li>
 *   <li>{@code claimsOutstanding} — sum of UNSETTLED {@code CLAIM_RECEIVABLE}
 *       amounts created in the window (loss/damage still to be recovered).</li>
 *   <li>{@code claimsRecovered} — sum of SETTLED {@code CLAIM_RECEIVABLE} amounts
 *       created in the window (already recovered from the courier).</li>
 *   <li>{@code codOutstanding} — sum of UNSETTLED {@code COD_RECEIVABLE} amounts
 *       created in the window (cash the courier has yet to remit; informational,
 *       not a cost).</li>
 *   <li>{@code totalExpenses} — sum of {@code expenses.amount} incurred in the
 *       window, with a per-category breakdown in {@code expenseByCategory}.</li>
 *   <li>{@code netProfit} = {@code revenue − courierCost − totalExpenses}.</li>
 * </ul>
 */
public record ProfitLossResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal revenue,
        BigDecimal courierCost,
        BigDecimal claimsOutstanding,
        BigDecimal claimsRecovered,
        BigDecimal codOutstanding,
        BigDecimal totalExpenses,
        List<ExpenseCategoryAmount> expenseByCategory,
        BigDecimal netProfit
) {

    /** A single {category, amount} row of the expense breakdown. */
    public record ExpenseCategoryAmount(String category, BigDecimal amount) {
    }
}
