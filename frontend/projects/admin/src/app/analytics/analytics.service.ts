import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  ForecastReport,
  RetentionReport,
  SalesTargetRow,
  SetSalesTargetRequest,
} from './analytics.model';

/**
 * Data access for the admin analytics suite (FEATURE-ROADMAP §6): sales targets
 * (§6.1), cohort/retention (§6.3), and demand/cash forecasting (§6.5).
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly api = inject(ApiClient);

  /** Sales targets vs achievement for a month (default current); §6.1. */
  targets(month?: string): Observable<SalesTargetRow[]> {
    const params = month ? { params: { month } } : undefined;
    return this.api.get<SalesTargetRow[]>('/api/admin/salespeople/targets', params);
  }

  /** Set/update a salesperson's monthly target + optional incentive; §6.1. */
  setTarget(request: SetSalesTargetRequest): Observable<SalesTargetRow> {
    return this.api.put<SalesTargetRow>('/api/admin/salespeople/targets', request);
  }

  /** Cohort / retention analysis over the last {@code months} cohorts; §6.3. */
  retention(months?: number): Observable<RetentionReport> {
    const params = months ? { params: { months } } : undefined;
    return this.api.get<RetentionReport>('/api/admin/analytics/retention', params);
  }

  /** Demand & cash forecast; §6.5. */
  forecast(lookbackDays?: number, horizonDays?: number): Observable<ForecastReport> {
    const params: Record<string, number> = {};
    if (lookbackDays) {
      params['lookbackDays'] = lookbackDays;
    }
    if (horizonDays) {
      params['horizonDays'] = horizonDays;
    }
    return this.api.get<ForecastReport>('/api/admin/analytics/forecast', { params });
  }
}
