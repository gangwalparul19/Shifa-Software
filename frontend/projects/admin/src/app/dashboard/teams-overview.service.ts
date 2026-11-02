import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { filenameFromContentDisposition } from '../shared/blob-download.util';
import { PeriodSelection, THIS_MONTH, periodQuery } from '../shared/period-selection';
import { labelledFilename } from '../shared/report-filename.util';
import { TeamsOverviewResponse } from './teams-overview.model';

/** File formats the team-wise sales report can be downloaded in. */
export type TeamsExportFormat = 'xlsx' | 'pdf';

/** Which teams a download covers: every team, the unassigned salespeople, or one team lead (by id). */
export type TeamsExportScope = 'all' | 'unassigned' | number;

/** A downloaded report: the bytes and the file name the server chose. */
export interface TeamsDownload {
  blob: Blob;
  filename: string;
}

/**
 * Data access for the "Team-wise sales with status" admin dashboard view
 * ({@code GET /api/admin/dashboard/teams}, ADMIN only).
 */
@Injectable({ providedIn: 'root' })
export class TeamsOverviewService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The overview with orders and revenue counted over the chosen period (this month by default). */
  overview(selection: PeriodSelection = THIS_MONTH): Observable<TeamsOverviewResponse> {
    return this.api.get<TeamsOverviewResponse>('/api/admin/dashboard/teams', {
      params: periodQuery(selection),
    });
  }

  /** Downloads the team-wise sales report for the chosen period as Excel or PDF. */
  exportReport(
    format: TeamsExportFormat,
    selection: PeriodSelection,
    scope: TeamsExportScope = 'all',
  ): Observable<TeamsDownload> {
    // Only used to name the file if the server's Content-Disposition cannot be read.
    const periodToken =
      selection.period === 'CUSTOM'
        ? `${selection.from}-to-${selection.to}`
        : selection.period.toLowerCase();
    const params = new HttpParams({
      fromObject: { ...periodQuery(selection), team: String(scope), format },
    });
    return new Observable<TeamsDownload>((subscriber) => {
      const subscription = this.http
        .get(this.api.url('/api/admin/dashboard/teams/export'), {
          params,
          observe: 'response',
          responseType: 'blob',
        })
        .subscribe({
          next: (response: HttpResponse<Blob>) => {
            subscriber.next({
              blob: response.body ?? new Blob(),
              filename:
                filenameFromContentDisposition(response.headers.get('Content-Disposition')) ??
                labelledFilename('Team-wise sales', periodToken, format),
            });
            subscriber.complete();
          },
          error: (error) => subscriber.error(error),
        });
      return () => subscription.unsubscribe();
    });
  }
}
