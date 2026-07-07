/** A single category line in the expense breakdown of a P&L report. */
export interface ExpenseCategoryAmount {
  category: string;
  amount: number;
}

/**
 * Profit &amp; Loss summary for a date range
 * ({@code GET /api/admin/finance/pnl}, ADMIN + ACCOUNTANT). Mirrors the backend
 * {@code ProfitLossResponse}.
 *
 * <p>Note: {@code courierCost} is derived from outstanding loss/damage claims
 * (per the backend javadoc), not a simple shipping-fee total.
 */
export interface ProfitLossResponse {
  from: string;
  to: string;
  revenue: number;
  courierCost: number;
  claimsOutstanding: number;
  claimsRecovered: number;
  codOutstanding: number;
  totalExpenses: number;
  expenseByCategory: ExpenseCategoryAmount[];
  netProfit: number;
}
