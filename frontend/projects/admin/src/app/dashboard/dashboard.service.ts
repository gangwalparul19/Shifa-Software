import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  ActivityCards,
  DashboardMetrics,
  LiveStats,
  MetricsPeriod,
  SalesBucket,
} from './dashboard.model';

/**
 * Data access for the admin dashboard metrics API (Req 19.1-19.7).
 *
 * <p>All calls go through the shared {@link ApiClient} so the global auth
 * interceptor attaches the bearer token; the backend enforces the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiClient);

  /**
   * Metric cards, sales graph, and top performers for a period (Req 19.1-19.4, 19.7).
   * For {@code CUSTOM}, pass {@code from}/{@code to} as ISO dates.
   */
  metrics(
    period: MetricsPeriod,
    bucket: SalesBucket | null,
    from: string | null,
    to: string | null,
  ): Observable<DashboardMetrics> {
    let params = new HttpParams().set('period', period);
    if (bucket) {
      params = params.set('bucket', bucket);
    }
    if (period === 'CUSTOM') {
      if (from) {
        params = params.set('from', from);
      }
      if (to) {
        params = params.set('to', to);
      }
    }
    return this.api.get<DashboardMetrics>('/api/admin/metrics', { params });
  }

  /** Real-time live statistics (Req 19.5). */
  liveStats(): Observable<LiveStats> {
    return this.api.get<LiveStats>('/api/admin/metrics/live');
  }

  /** Activity-card counts (Req 19.6). */
  activity(): Observable<ActivityCards> {
    return this.api.get<ActivityCards>('/api/admin/metrics/activity');
  }
}
