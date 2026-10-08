import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { Insight, InsightListQuery, RecomputeResponse } from './insights.model';

/**
 * Data access for the Statistical Insights API (`/api/insights`, design §API).
 * All calls go through the shared {@link ApiClient} so the global auth
 * interceptor attaches the bearer token; the backend restricts every route to
 * ADMIN + SALESPERSON, scopes the listing server-side, and further gates
 * {@link dismiss}/{@link recompute} to ADMIN only.
 */
@Injectable({ providedIn: 'root' })
export class InsightsService {
  private readonly api = inject(ApiClient);

  /** Latest-date insights, role-scoped, with optional type/scope/severity/dismissed filters (Req 9.1, 9.2). */
  list(filters: InsightListQuery = {}): Observable<Insight[]> {
    const params: Record<string, string> = {};
    if (filters.type) {
      params['type'] = filters.type;
    }
    if (filters.scope) {
      params['scope'] = filters.scope;
    }
    if (filters.severity) {
      params['severity'] = filters.severity;
    }
    if (filters.includeDismissed) {
      params['includeDismissed'] = 'true';
    }
    return this.api.get<Insight[]>(
      '/api/insights',
      Object.keys(params).length ? { params } : undefined,
    );
  }

  /** Dismiss one insight; ADMIN-only, idempotent (Req 9.3). */
  dismiss(id: number): Observable<Insight> {
    return this.api.post<Insight>(`/api/insights/${id}/dismiss`);
  }

  /** Run the computation now; ADMIN-only (Req 2.2). */
  recompute(): Observable<RecomputeResponse> {
    return this.api.post<RecomputeResponse>('/api/insights/recompute');
  }
}
