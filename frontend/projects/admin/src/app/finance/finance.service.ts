import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { ProfitLossResponse } from './finance.model';

/**
 * Data access for the admin Profit &amp; Loss report
 * ({@code /api/admin/finance/pnl}, ADMIN + ACCOUNTANT). Calls go through the
 * shared {@link ApiClient}; the auth interceptor attaches the bearer token and
 * the backend enforces the roles.
 */
@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly api = inject(ApiClient);

  /** Profit &amp; Loss for an inclusive date range (both yyyy-MM-dd, required). */
  profitLoss(from: string, to: string): Observable<ProfitLossResponse> {
    return this.api.get<ProfitLossResponse>('/api/admin/finance/pnl', {
      params: { from, to },
    });
  }
}
