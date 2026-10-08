import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  Amendment,
  AmendmentReviewRequest,
  AmendmentStatus,
  CalendarResponse,
  DrillDownRow,
  FileReturnRequest,
  FilingStatusResponse,
  PrepareReturnRequest,
  ReconciliationSummary,
  ReopenReturnRequest,
  ReturnType,
  Snapshot,
} from './filing.model';

/**
 * Data access for the Phase-3 GST returns & filing endpoints (`/api/ca/gst/filing/**` and
 * `/api/ca/gst/reconciliation/**`, ADMIN + CA). JSON calls go through the shared {@link ApiClient};
 * the filing-aware GSTR-1 export is fetched as a Blob via {@link HttpClient} so the auth interceptor
 * attaches the bearer token — mirroring {@code GstService} exactly.
 */
@Injectable({ providedIn: 'root' })
export class FilingService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /* ── Filing status lifecycle ─────────────────────────────────────────────── */

  /** The current GSTR-1 and GSTR-3B filing status for a selected period (Reqs 1.2, 1.6). */
  status(month: number, year: number): Observable<FilingStatusResponse> {
    return this.api.get<FilingStatusResponse>('/api/ca/gst/filing/status', {
      params: { month: String(month), year: String(year) },
    });
  }

  /** Marks a return prepared: NOT_STARTED → PREPARED (Reqs 1.3, 1.7). */
  prepare(request: PrepareReturnRequest): Observable<FilingStatusResponse> {
    return this.api.post<FilingStatusResponse>('/api/ca/gst/filing/prepare', request);
  }

  /** Files a prepared return: PREPARED → FILED, with an optional ack reference (Reqs 1.4, 1.5). */
  file(request: FileReturnRequest): Observable<FilingStatusResponse> {
    return this.api.post<FilingStatusResponse>('/api/ca/gst/filing/file', request);
  }

  /** Reopens a filed return: FILED → PREPARED, ADMIN/CA only (Reqs 2.4, 2.7). */
  reopen(request: ReopenReturnRequest): Observable<FilingStatusResponse> {
    return this.api.post<FilingStatusResponse>('/api/ca/gst/filing/reopen', request);
  }

  /* ── Snapshots & calendar ────────────────────────────────────────────────── */

  /** The filing-snapshot history for a period and return type, in filing (version) order (Req 5.7). */
  snapshots(month: number, year: number, returnType: ReturnType): Observable<Snapshot[]> {
    return this.api.get<Snapshot[]>('/api/ca/gst/filing/snapshots', {
      params: { month: String(month), year: String(year), returnType },
    });
  }

  /** The GST filing calendar for an Indian financial year (Reqs 4.1–4.6). */
  calendar(fy: number): Observable<CalendarResponse> {
    return this.api.get<CalendarResponse>('/api/ca/gst/filing/calendar', {
      params: { fy: String(fy) },
    });
  }

  /**
   * The filing-aware GSTR-1 export for a period: {@code csv} downloads the ZIP of section CSVs,
   * {@code json} downloads the portal JSON (served from the snapshot when FILED, else computed).
   * Fetched as a Blob so the auth interceptor attaches the bearer token (Reqs 6.1–6.5).
   */
  exportGstr1(month: number, year: number, format: 'csv' | 'json'): Observable<Blob> {
    const params = new HttpParams()
      .set('month', String(month))
      .set('year', String(year))
      .set('format', format);
    return this.http.get(this.api.url('/api/ca/gst/filing/gstr1/export'), {
      params,
      responseType: 'blob',
    });
  }

  /* ── Reconciliation ──────────────────────────────────────────────────────── */

  /**
   * The reconciliation summary for a period — the five compared figures against the GST control
   * ledgers and the P&L revenue (Reqs 7, 8, 9.1). When month/year are omitted the current month is
   * used by the backend.
   */
  reconcile(month?: number | null, year?: number | null): Observable<ReconciliationSummary> {
    const p: Record<string, string> = {};
    if (month !== null && month !== undefined) {
      p['month'] = String(month);
    }
    if (year !== null && year !== undefined) {
      p['year'] = String(year);
    }
    return this.api.get<ReconciliationSummary>('/api/ca/gst/reconciliation', { params: p });
  }

  /** The rows contributing to a compared figure for the period (drill-down, Reqs 9.3, 9.7). */
  drillDown(month: number, year: number, figure: string): Observable<DrillDownRow[]> {
    return this.api.get<DrillDownRow[]>('/api/ca/gst/reconciliation/drill-down', {
      params: { month: String(month), year: String(year), figure },
    });
  }

  /* ── Amendments ──────────────────────────────────────────────────────────── */

  /** The post-filing amendment queue, optionally filtered by Filed_Period year and/or status (Req 3). */
  amendments(year?: number | null, status?: AmendmentStatus | null): Observable<Amendment[]> {
    const p: Record<string, string> = {};
    if (year !== null && year !== undefined) {
      p['year'] = String(year);
    }
    if (status) {
      p['status'] = status;
    }
    return this.api.get<Amendment[]>('/api/ca/gst/amendments', { params: p });
  }

  /** Resolves a manual-review amendment by routing it into a table + open target period (Reqs 3.5, 3.7). */
  reviewAmendment(id: number, request: AmendmentReviewRequest): Observable<Amendment> {
    return this.api.post<Amendment>(`/api/ca/gst/amendments/${id}/review`, request);
  }
}
