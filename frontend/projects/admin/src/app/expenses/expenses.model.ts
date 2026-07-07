/**
 * A business expense returned by the admin expenses endpoints
 * ({@code /api/admin/expenses}, ADMIN + ACCOUNTANT). Mirrors the backend
 * {@code ExpenseResponse}.
 */
export interface ExpenseResponse {
  id: number;
  category: string;
  description?: string | null;
  amount: number;
  /** The date the expense was incurred (yyyy-MM-dd). */
  incurredOn: string;
  createdBy: number;
  createdAt: string;
}

/**
 * Payload for recording an expense ({@code POST /api/admin/expenses}).
 * Mirrors the backend {@code ExpenseRequest}.
 */
export interface ExpenseRequest {
  category: string;
  description?: string | null;
  amount: number;
  /** yyyy-MM-dd. */
  incurredOn: string;
}
