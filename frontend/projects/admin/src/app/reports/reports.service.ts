import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { ExportFormat, ReportResponse, ReportType, VyaparFormat } from './reports.model';

/** A downloaded export: the file blob, its filename, and any server message. */
export interface DownloadResult {
  blob: Blob;
  filename: string;
  /** The {@code X-Report-Message} header, e.g. the Vyapar "no orders" notice (Req 23.2). */
  message: string | null;
}

/**
 * Data access for the reporting/export API (Req 20.1-20.4, 23.1, 23.2).
 *
 * <p>JSON report reads go through the shared {@link ApiClient}; file exports use
 * {@link HttpClient} directly with a {@code blob} response so the downloaded
 * bytes and the {@code X-Report-Message} header (empty Vyapar range notice) are
 * both available. Both paths run through the global auth interceptor, so the
 * bearer token is attached and the server enforces role + salesperson scoping.
 */
@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** A report as JSON, restricted to the optional date window (Req 20.1-20.3). */
  report(type: ReportType, from: string | null, to: string | null): Observable<ReportResponse> {
    return this.api.get<ReportResponse>(`/api/reports/${type}`, {
      params: this.rangeParams(from, to),
    });
  }

  /** Download the current report as an Excel or PDF file (Req 20.4). */
  export(
    type: ReportType,
    format: ExportFormat,
    from: string | null,
    to: string | null,
  ): Observable<DownloadResult> {
    let params = this.rangeParams(from, to);
    params = params.set('type', type).set('format', format);
    return this.download('/api/reports/export', params);
  }

  /** Download the Vyapar billing export for a date range (Req 23.1, 23.2). */
  vyapar(format: VyaparFormat, from: string | null, to: string | null): Observable<DownloadResult> {
    let params = this.rangeParams(from, to);
    params = params.set('format', format);
    return this.download('/api/reports/vyapar', params);
  }

  private download(path: string, params: HttpParams): Observable<DownloadResult> {
    return new Observable<DownloadResult>((subscriber) => {
      const sub = this.http
        .get(this.api.url(path), { params, observe: 'response', responseType: 'blob' })
        .subscribe({
          next: (response: HttpResponse<Blob>) => {
            subscriber.next({
              blob: response.body ?? new Blob(),
              filename: this.filenameOf(response),
              message: response.headers.get('X-Report-Message'),
            });
            subscriber.complete();
          },
          error: (err) => subscriber.error(err),
        });
      return () => sub.unsubscribe();
    });
  }

  private rangeParams(from: string | null, to: string | null): HttpParams {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return params;
  }

  private filenameOf(response: HttpResponse<Blob>): string {
    const disposition = response.headers.get('Content-Disposition');
    if (disposition) {
      const match = /filename="?([^"]+)"?/i.exec(disposition);
      if (match) {
        return match[1];
      }
    }
    return 'report';
  }
}
