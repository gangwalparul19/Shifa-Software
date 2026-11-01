import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { filenameFromContentDisposition } from '../shared/blob-download.util';
import { reportFilename } from '../shared/report-filename.util';
import { ExportFormat, ReportResponse, ReportType } from './reports.model';

/** A downloaded export: the file blob and its filename. */
export interface DownloadResult {
  blob: Blob;
  filename: string;
}

/**
 * Data access for the reporting/export API (Req 20.1-20.4).
 *
 * <p>JSON report reads go through the shared {@link ApiClient}; file exports use
 * {@link HttpClient} directly with a {@code blob} response so the downloaded
 * bytes and the {@code Content-Disposition} filename are both available. Both
 * paths run through the global auth interceptor, so the bearer token is attached
 * and the server enforces role + salesperson scoping.
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
    // The server names the file after the report and its dates; this is only the
    // fallback if the browser cannot read that header, and is still descriptive.
    return this.download('/api/reports/export', params, reportFilename(type, from, to, format));
  }

  private download(
    path: string,
    params: HttpParams,
    fallbackFilename: string,
  ): Observable<DownloadResult> {
    return new Observable<DownloadResult>((subscriber) => {
      const sub = this.http
        .get(this.api.url(path), { params, observe: 'response', responseType: 'blob' })
        .subscribe({
          next: (response: HttpResponse<Blob>) => {
            subscriber.next({
              blob: response.body ?? new Blob(),
              filename:
                filenameFromContentDisposition(response.headers.get('Content-Disposition')) ??
                fallbackFilename,
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
}
