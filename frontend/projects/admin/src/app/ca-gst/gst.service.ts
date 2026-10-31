import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AuthenticatedBlobService } from '../shared/authenticated-blob.service';
import { Gstr1ReturnResponse, GstDashboard, GstOrderFilter, GstOrderRow, GstReport } from './gst.model';

/**
 * Data access for the CA GST/accounting dashboard ({@code /api/ca/gst},
 * JSON calls go through the shared {@link ApiClient}; binary exports use the
 * shared authenticated blob transport so the auth interceptor remains the
 * single bearer-token path.
 */
@Injectable({ providedIn: 'root' })
export class GstService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

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
    return this.blobs.get('/api/ca/gst/report/export', { params });
  }

  /** The portal-ready GSTR-1 return for the period (defaults to current month). */
  gstr1(from?: string | null, to?: string | null): Observable<Gstr1ReturnResponse> {
    return this.api.get<Gstr1ReturnResponse>('/api/ca/gst/gstr1', { params: this.range(from, to) });
  }

  /**
   * The GSTR-1 export for the period: {@code csv} downloads the ZIP of section CSVs,
   * {@code json} downloads the portal JSON. Fetched as a Blob so the auth interceptor
   * attaches the bearer token.
   */
  exportGstr1(from: string | null, to: string | null, format: 'csv' | 'json'): Observable<Blob> {
    let params = new HttpParams().set('format', format);
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.blobs.get('/api/ca/gst/gstr1/export', { params });
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
