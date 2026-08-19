import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { GstDashboard, GstOrderFilter, GstOrderRow, GstReport } from './gst.model';

/**
 * Data access for the CA GST/accounting dashboard ({@code /api/ca/gst},
 * ADMIN + CA + ACCOUNTANT). JSON calls go through the shared {@link ApiClient};
 * the CSV export is fetched as a Blob via {@link HttpClient} so the auth
 * interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class GstService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The dashboard: GST report + money in/out for the period (defaults to current month). */
  dashboard(from?: string | null, to?: string | null): Observable<GstDashboard> {
    return this.api.get<GstDashboard>('/api/ca/gst/dashboard', { params: this.range(from, to) });
  }

  /** The filing-ready outward GST report for the period. */
  report(from?: string | null, to?: string | null): Observable<GstReport> {
    return this.api.get<GstReport>('/api/ca/gst/report', { params: this.range(from, to) });
  }

  /** The GST report exported as a CSV or PDF blob for the period. */
  exportReport(from: string | null, to: string | null, format: 'csv' | 'pdf'): Observable<Blob> {
    let params = new HttpParams().set('format', format);
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.http.get(this.api.url('/api/ca/gst/report/export'), {
      params,
      responseType: 'blob',
    });
  }

  /** The orders behind a GST figure for the period (drill-down), optionally filtered. */
  orders(from: string | null, to: string | null, filter: GstOrderFilter): Observable<GstOrderRow[]> {
    const p: Record<string, string> = {};
    if (from) {
      p['from'] = from;
    }
    if (to) {
      p['to'] = to;
    }
    if (filter.state) {
      p['state'] = filter.state;
    }
    if (filter.rate !== null && filter.rate !== undefined && filter.rate !== '') {
      p['rate'] = String(filter.rate);
    }
    if (filter.hsn) {
      p['hsn'] = filter.hsn;
    }
    return this.api.get<GstOrderRow[]>('/api/ca/gst/orders', { params: p });
  }

  private range(from?: string | null, to?: string | null): Record<string, string> {
    const p: Record<string, string> = {};
    if (from) {
      p['from'] = from;
    }
    if (to) {
      p['to'] = to;
    }
    return p;
  }
}
