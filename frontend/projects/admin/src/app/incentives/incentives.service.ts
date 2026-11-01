import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AuthenticatedBlobService, BlobDownload } from '../shared/authenticated-blob.service';
import {
  IncentiveReportFormat,
  IncentiveReportKind,
  IncentiveRules,
  IncentiveRulesRequest,
  IncentiveTracker,
  ProductReport,
} from './incentives.model';

/** What a report download needs; only the fields its kind uses are sent. */
export interface IncentiveReportQuery {
  month?: string;
  from?: string;
  to?: string;
  personId?: number | null;
}

/** Data access for the daily sales tracker and the daily incentive rules (ADMIN only). */
@Injectable({ providedIn: 'root' })
export class IncentivesService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

  /** Everyone's daily sales and bonus for a month (yyyy-MM). */
  tracker(month: string): Observable<IncentiveTracker> {
    return this.api.get<IncentiveTracker>('/api/admin/incentives/tracker', { params: { month } });
  }

  /** Product sales per salesperson per day. */
  products(from: string, to: string, personId?: number | null): Observable<ProductReport> {
    const params: Record<string, string> = { from, to };
    if (personId != null) {
      params['personId'] = String(personId);
    }
    return this.api.get<ProductReport>('/api/admin/incentives/products', { params });
  }

  /** Excel or PDF of a report, with the filename the server chose. */
  download(
    report: IncentiveReportKind,
    format: IncentiveReportFormat,
    query: IncentiveReportQuery,
  ): Observable<BlobDownload> {
    const params: Record<string, string> = { report, format };
    if (query.month) params['month'] = query.month;
    if (query.from) params['from'] = query.from;
    if (query.to) params['to'] = query.to;
    if (query.personId != null) params['personId'] = String(query.personId);
    return this.blobs.download('/api/admin/incentives/export', { params });
  }

  /** Saves a finished download through the browser. */
  save(download: BlobDownload, report: IncentiveReportKind, format: IncentiveReportFormat): void {
    this.blobs.save(download.blob, download.filename, `shifa-${report}-incentives.${format}`);
  }

  /** The rules in force today and every earlier or scheduled version. */
  rules(): Observable<IncentiveRules> {
    return this.api.get<IncentiveRules>('/api/admin/settings/incentive-rules');
  }

  /** Saves a new version of the rules; earlier versions are kept as the audit trail. */
  saveRules(request: IncentiveRulesRequest): Observable<IncentiveRules> {
    return this.api.post<IncentiveRules>('/api/admin/settings/incentive-rules', request);
  }
}
