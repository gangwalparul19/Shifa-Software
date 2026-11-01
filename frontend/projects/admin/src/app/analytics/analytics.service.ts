import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin } from 'rxjs';
import { ApiClient } from 'core';
import { AuthenticatedBlobService, BlobDownload } from '../shared/authenticated-blob.service';
import {
  CustomerSegmentsReport,
  DatasetInfo,
  DeliveryPerformanceReport,
  ForecastReport,
  LeadConversionReport,
  LeadFunnel,
  LeadLostReasonReport,
  LeadPipelineReport,
  OperationsReport,
  OverviewReport,
  PointersReport,
  ProductPerformanceReport,
  RetentionReport,
  SalesTargetRow,
  SetPeriodTargetRequest,
  SetSalesTargetRequest,
  SetTeamTargetRequest,
} from './analytics.model';

/**
 * Data access for the admin analytics suite (FEATURE-ROADMAP §6): sales targets
 * (§6.1), cohort/retention (§6.3), and demand/cash forecasting (§6.5).
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

  /** Sales targets vs achievement for a month (default current); §6.1. */
  targets(month?: string): Observable<SalesTargetRow[]> {
    const params = month ? { params: { month } } : undefined;
    return this.api.get<SalesTargetRow[]>('/api/admin/salespeople/targets', params);
  }

  /** Set/update a salesperson's monthly target + optional incentive; §6.1. */
  setTarget(request: SetSalesTargetRequest): Observable<SalesTargetRow> {
    return this.api.put<SalesTargetRow>('/api/admin/salespeople/targets', request);
  }

  /** Weekly per-salesperson targets vs achievement (CRM expansion Req 12). */
  weeklyTargets(week?: string): Observable<SalesTargetRow[]> {
    const params = week ? { params: { week } } : undefined;
    return this.api.get<SalesTargetRow[]>('/api/admin/salespeople/targets/weekly', params);
  }

  /** Set/update a per-salesperson WEEK or MONTH target (CRM expansion Req 12). */
  setPeriodTarget(request: SetPeriodTargetRequest): Observable<SalesTargetRow> {
    return this.api.put<SalesTargetRow>('/api/admin/salespeople/targets/period', request);
  }

  /** Set/update a TEAM target keyed by the team lead (CRM expansion Req 12). */
  setTeamTarget(request: SetTeamTargetRequest): Observable<SalesTargetRow> {
    return this.api.put<SalesTargetRow>('/api/admin/salespeople/targets/team', request);
  }

  /** Cohort / retention analysis over the last {@code months} cohorts; §6.3. */
  retention(months?: number): Observable<RetentionReport> {
    const params = months ? { params: { months } } : undefined;
    return this.api.get<RetentionReport>('/api/admin/analytics/retention', params);
  }

  /** Demand & cash forecast; §6.5. */
  forecast(lookbackDays?: number, horizonDays?: number): Observable<ForecastReport> {
    const params: Record<string, number> = {};
    if (lookbackDays) {
      params['lookbackDays'] = lookbackDays;
    }
    if (horizonDays) {
      params['horizonDays'] = horizonDays;
    }
    return this.api.get<ForecastReport>('/api/admin/analytics/forecast', { params });
  }

  /** Delivery-performance analytics by courier / state / pincode band; ENHANCEMENT 3.3. */
  deliveryPerformance(): Observable<DeliveryPerformanceReport> {
    return this.api.get<DeliveryPerformanceReport>('/api/admin/analytics/delivery-performance');
  }

  // --- Growth analytics -----------------------------------------------------
  // `from` / `to` are IST dates (yyyy-MM-dd), inclusive; both omitted means the last 30 days.

  /** Sales, customers, mixes, destinations and order timing, against the previous period. */
  overview(from: string | null, to: string | null): Observable<OverviewReport> {
    return this.api.get<OverviewReport>('/api/admin/analytics/overview', { params: this.range(from, to) });
  }

  /** Customer engagement segments and value concentration (all time). */
  customers(): Observable<CustomerSegmentsReport> {
    return this.api.get<CustomerSegmentsReport>('/api/admin/analytics/customers');
  }

  /** Product performance, ABC classes and products bought together. */
  products(from: string | null, to: string | null): Observable<ProductPerformanceReport> {
    return this.api.get<ProductPerformanceReport>('/api/admin/analytics/products', {
      params: this.range(from, to),
    });
  }

  /** Fulfilment speed, order outcomes, payment risk and rejection reasons. */
  operations(from: string | null, to: string | null): Observable<OperationsReport> {
    return this.api.get<OperationsReport>('/api/admin/analytics/operations', {
      params: this.range(from, to),
    });
  }

  /** The key things worth acting on in the period, ranked. */
  pointers(from: string | null, to: string | null): Observable<PointersReport> {
    return this.api.get<PointersReport>('/api/admin/analytics/pointers', { params: this.range(from, to) });
  }

  /** The lead funnel for the period (existing lead report endpoints, loaded together). */
  leadFunnel(from: string | null, to: string | null): Observable<LeadFunnel> {
    const params = { params: this.range(from, to) };
    return forkJoin({
      conversion: this.api.get<LeadConversionReport>('/api/leads/reports/conversion', params),
      lostReasons: this.api.get<LeadLostReasonReport>('/api/leads/reports/lost-reasons', params),
      pipeline: this.api.get<LeadPipelineReport>('/api/leads/reports/pipeline'),
    });
  }

  /** The downloadable datasets and their documented columns. */
  datasets(): Observable<DatasetInfo[]> {
    return this.api.get<DatasetInfo[]>('/api/admin/analytics/datasets');
  }

  /** One dataset as CSV, with the filename the server chose. */
  downloadDataset(key: string, from: string | null, to: string | null): Observable<BlobDownload> {
    return this.blobs.download(`/api/admin/analytics/datasets/${encodeURIComponent(key)}/export`, {
      params: this.range(from, to),
    });
  }

  /** Saves a downloaded dataset through the browser. */
  saveDataset(download: BlobDownload, key: string): void {
    this.blobs.save(download.blob, download.filename, `shifa-data-${key}.csv`);
  }

  private range(from: string | null, to: string | null): Record<string, string> {
    const params: Record<string, string> = {};
    if (from) {
      params['from'] = from;
    }
    if (to) {
      params['to'] = to;
    }
    return params;
  }
}
