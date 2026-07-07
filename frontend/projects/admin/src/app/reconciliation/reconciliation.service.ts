import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse, ReceivableType } from 'core';
import { CourierSummary, ReceivableRow, Segregation, UnsettledCod } from './reconciliation.model';

/** Filters for the receivables listing (Req 18.1-18.3). */
export interface ReceivableFilters {
  courier?: number | null;
  type?: ReceivableType | null;
  settled?: boolean | null;
}

/** Filters + paging for the server-side receivables page (Wave 2). */
export interface ReceivablePageQuery {
  courier?: number | null;
  type?: ReceivableType | string | null;
  settled?: boolean | null;
  q?: string | null;
  from?: string | null;
  to?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/**
 * Data access for the COD/loss reconciliation dashboard (Req 17.4, 18.1-18.6).
 *
 * <p>All calls go through the shared {@link ApiClient} (the auth interceptor
 * attaches the bearer token) against the {@code /api/recon/**} endpoints, which
 * are restricted server-side to ACCOUNTANT/ADMIN.
 */
@Injectable({ providedIn: 'root' })
export class ReconciliationService {
  private readonly api = inject(ApiClient);

  /** Per-courier COD/claim outstanding totals (Req 18.1, 18.2, 18.6). */
  summary(): Observable<CourierSummary[]> {
    return this.api.get<CourierSummary[]>('/api/recon/summary');
  }

  /**
   * Receivables list, optionally filtered by courier company and type
   * (Req 18.1-18.3). The {@code settled} filter is applied client-side since the
   * list endpoint returns both settled and unsettled rows.
   */
  receivables(filters?: ReceivableFilters): Observable<ReceivableRow[]> {
    const params: Record<string, string | number> = {};
    if (filters?.courier != null) {
      params['courier'] = filters.courier;
    }
    if (filters?.type != null) {
      params['type'] = filters.type;
    }
    return this.api.get<ReceivableRow[]>('/api/recon/receivables', { params });
  }

  /**
   * Server-side paginated, filtered, sorted receivables (Wave 2). Backed by
   * {@code GET /api/recon/receivables/page}. Empty/nullish filters are omitted.
   */
  receivablesPage(query: ReceivablePageQuery): Observable<PageResponse<ReceivableRow>> {
    const params: Record<string, string | number | boolean> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    if (query.courier != null) {
      params['courier'] = query.courier;
    }
    if (query.type) {
      params['type'] = query.type;
    }
    if (query.settled != null) {
      params['settled'] = query.settled;
    }
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (query.from) {
      params['from'] = query.from;
    }
    if (query.to) {
      params['to'] = query.to;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<ReceivableRow>>('/api/recon/receivables/page', { params });
  }

  /** Delivered COD orders whose receivable is unsettled (Req 18.3). */
  unsettledCod(): Observable<UnsettledCod[]> {
    return this.api.get<UnsettledCod[]>('/api/recon/cod/unsettled');
  }

  /** Prepaid vs COD segregation of fulfilled orders (Req 18.4). */
  segregation(): Observable<Segregation> {
    return this.api.get<Segregation>('/api/recon/segregation');
  }

  /** Unsettled claims that need filing against their courier/AWB (Req 17.4). */
  pendingClaims(): Observable<ReceivableRow[]> {
    return this.api.get<ReceivableRow[]>('/api/recon/claims/pending');
  }

  /**
   * Mark a receivable settled with an optional date; idempotent (Req 18.5).
   *
   * @param receivableId the receivable to settle
   * @param date         an ISO date (yyyy-MM-dd), or omitted to default to today
   */
  settle(receivableId: number, date?: string | null): Observable<ReceivableRow> {
    const body = date ? { date } : {};
    return this.api.post<ReceivableRow>(`/api/recon/receivables/${receivableId}/settle`, body);
  }
}
