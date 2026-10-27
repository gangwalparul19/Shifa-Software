import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse, ReceivableType } from 'core';
import {
  CodAging,
  CollectibleSummary,
  CourierSummary,
  ReceivableRow,
  Segregation,
  UnsettledCod,
} from './reconciliation.model';

/** The outcome classification for a single courier remittance row. */
export type RemittanceRowStatus =
  | 'SETTLED'
  | 'PARTIALLY_SETTLED'
  | 'MISMATCH'
  | 'ALREADY_SETTLED'
  | 'ORDER_NOT_FOUND'
  | 'NO_RECEIVABLE'
  | 'ERROR';

/** A committed remittance import summary (backend {@code RemittanceBatchResponse}). */
export interface RemittanceBatch {
  id: number;
  originalFilename?: string | null;
  externalReference?: string | null;
  remittedDate?: string | null;
  importedAt?: string | null;
  totalRows: number;
  settledCount: number;
  partialCount: number;
  mismatchCount: number;
  alreadySettledCount: number;
  notFoundCount: number;
  noReceivableCount: number;
  errorCount: number;
  totalRemitted: number | string;
  totalSettled: number | string;
  totalShortfall: number | string;
}

/** A per-order line within a committed remittance batch (backend {@code RemittanceBatchLineResponse}). */
export interface RemittanceBatchLine {
  id: number;
  batchId?: number | null;
  sourceRowNumber: number;
  awb?: string | null;
  clientOrderId?: string | null;
  customerName?: string | null;
  resolvedOrderId?: number | null;
  resolvedOrderCode?: string | null;
  expectedAmount?: number | string | null;
  remittedAmount?: number | string | null;
  settledAmount: number | string;
  shortfallAmount: number | string;
  remittedDate?: string | null;
  status: RemittanceRowStatus;
  message?: string | null;
}

/**
 * A partially-settled COD order to chase next cycle (backend
 * {@code PartialSettlementResponse}): the courier remittance was short, so part
 * was settled and a shortfall stays outstanding.
 */
export interface PartialSettlement {
  receivableId: number;
  orderId: number;
  orderCode?: string | null;
  customerName?: string | null;
  awb?: string | null;
  expectedAmount: number | string;
  settledAmount: number | string;
  shortfall: number | string;
  settledDate?: string | null;
}

/** KPI summary for the settlement report (backend {@code RemittanceReportSummaryResponse}). */
export interface RemittanceReportSummary {
  from?: string | null;
  to?: string | null;
  batchCount: number;
  ordersSettled: number;
  ordersPartiallySettled: number;
  ordersNeedingReview: number;
  totalRemitted: number | string;
  totalSettled: number | string;
  totalShortfall: number | string;
  codStillOutstanding: number | string;
}

/** A single row's outcome in a courier COD remittance import (dry-run or real). */
export interface RemittanceRowResult {
  rowNumber: number;
  awb?: string | null;
  orderCode?: string | null;
  clientOrderId?: string | null;
  customerName?: string | null;
  resolvedOrderCode?: string | null;
  resolvedOrderId?: number | null;
  remittedAmount?: number | string | null;
  expectedAmount?: number | string | null;
  settledAmount?: number | string | null;
  shortfallAmount?: number | string | null;
  remittedDate?: string | null;
  status: RemittanceRowStatus;
  message?: string | null;
}

/**
 * Result of a courier COD remittance CSV import
 * ({@code POST /api/recon/remittance/import}). When {@code dryRun} is true the
 * counts + rows describe what *would* happen (nothing settled); on a commit
 * {@code batchId} is the persisted import's id.
 */
export interface RemittanceImportResult {
  dryRun: boolean;
  batchId?: number | null;
  totalRows: number;
  settled: number;
  partiallySettled: number;
  mismatched: number;
  alreadySettled: number;
  notFound: number;
  noReceivable: number;
  errors: number;
  totalRemitted: number | string;
  totalSettled: number | string;
  totalShortfall: number | string;
  rows: RemittanceRowResult[];
}

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

  /** COD aging buckets + courier-SLA flag (cod-aging enhancement). */
  codAging(): Observable<CodAging> {
    return this.api.get<CodAging>('/api/recon/cod-aging');
  }

  /** One-tap collectible summary — courier-COD vs customer dues + claims (ENHANCEMENT 1.4). */
  collectibleSummary(): Observable<CollectibleSummary> {
    return this.api.get<CollectibleSummary>('/api/recon/collectible-summary');
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

  /**
   * Imports (or previews) a courier COD remittance sheet — CSV or a real Excel
   * workbook ({@code .xlsx}/{@code .xls}, e.g. QuikShipX's own remittance
   * export) — via {@code POST /api/recon/remittance/import} (ADMIN +
   * ACCOUNTANT). Posts the file as the multipart part {@code file}; pass
   * {@code dryRun=true} first to preview the auto-match, then {@code false} to
   * commit the settlements. Angular sets the multipart {@code Content-Type}
   * itself for a {@link FormData} body.
   */
  importRemittance(file: File, dryRun: boolean): Observable<RemittanceImportResult> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<RemittanceImportResult>('/api/recon/remittance/import', form, {
      params: { dryRun },
    });
  }

  /**
   * Weekly COD settlement report KPIs ({@code GET /api/recon/remittance/summary}).
   * With no dates, the backend defaults to the current week.
   */
  remittanceSummary(from?: string | null, to?: string | null): Observable<RemittanceReportSummary> {
    const params: Record<string, string> = {};
    if (from) {
      params['from'] = from;
    }
    if (to) {
      params['to'] = to;
    }
    return this.api.get<RemittanceReportSummary>('/api/recon/remittance/summary', { params });
  }

  /** Committed remittance import batches, optionally windowed by import date. */
  remittanceBatches(from?: string | null, to?: string | null): Observable<RemittanceBatch[]> {
    const params: Record<string, string> = {};
    if (from) {
      params['from'] = from;
    }
    if (to) {
      params['to'] = to;
    }
    return this.api.get<RemittanceBatch[]>('/api/recon/remittance/batches', { params });
  }

  /** Per-order lines of one committed remittance batch (the details report). */
  remittanceBatchLines(batchId: number): Observable<RemittanceBatchLine[]> {
    return this.api.get<RemittanceBatchLine[]>(`/api/recon/remittance/batches/${batchId}/lines`);
  }

  /**
   * Partially-settled COD orders with a shortfall still outstanding — the
   * "chase next cycle" list ({@code GET /api/recon/remittance/partial}).
   */
  partiallySettled(): Observable<PartialSettlement[]> {
    return this.api.get<PartialSettlement[]>('/api/recon/remittance/partial');
  }
}
