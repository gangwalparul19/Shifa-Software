import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  BySourceReport,
  ConversionReport,
  CreateLeadRequest,
  FollowUpRequest,
  LeadConvertRequest,
  LeadDetail,
  LeadListQuery,
  LeadStatus,
  LeadStatusChangeRequest,
  LeadSummary,
  LostReasonReport,
  PipelineReport,
} from './leads.model';

/**
 * Data access for the Lead Management / sales-pipeline API (`/api/leads`,
 * design §API). All calls go through the shared {@link ApiClient} so the global
 * auth interceptor attaches the bearer token; the backend restricts every route
 * to SALESPERSON + ADMIN and scopes a salesperson to their own leads server-side.
 */
@Injectable({ providedIn: 'root' })
export class LeadsService {
  private readonly api = inject(ApiClient);

  /** Capture a new lead (Req 1). Returns the created {@link LeadDetail} (status NEW). */
  capture(payload: CreateLeadRequest): Observable<LeadDetail> {
    return this.api.post<LeadDetail>('/api/leads', payload);
  }

  /** Scoped lead list with optional name/mobile search + status/source filters (Req 3.4). */
  list(query: LeadListQuery = {}): Observable<LeadSummary[]> {
    const params: Record<string, string> = {};
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (query.status) {
      params['status'] = query.status;
    }
    if (query.source) {
      params['source'] = query.source;
    }
    return this.api.get<LeadSummary[]>(
      '/api/leads',
      Object.keys(params).length ? { params } : undefined,
    );
  }

  /** Active-lead pipeline counts per {@link LeadStatus} (Req 3.3), scoped. */
  pipeline(): Observable<Record<LeadStatus, number>> {
    return this.api.get<Record<LeadStatus, number>>('/api/leads/pipeline');
  }

  /** The acting user's due follow-ups: non-terminal leads due on/before today (Req 5.2). */
  dueFollowUps(): Observable<LeadSummary[]> {
    return this.api.get<LeadSummary[]>('/api/leads/follow-ups/due');
  }

  /** Scoped lead detail incl. status history (Req 3.5); 404 when out of scope. */
  detail(id: number): Observable<LeadDetail> {
    return this.api.get<LeadDetail>(`/api/leads/${id}`);
  }

  /** Advance status / mark LOST (Req 2.2-2.7); 409 on illegal/terminal. */
  changeStatus(id: number, payload: LeadStatusChangeRequest): Observable<LeadDetail> {
    return this.api.post<LeadDetail>(`/api/leads/${id}/status`, payload);
  }

  /** Set or clear the follow-up date (Req 5.1). */
  setFollowUp(id: number, payload: FollowUpRequest): Observable<LeadDetail> {
    return this.api.put<LeadDetail>(`/api/leads/${id}/follow-up`, payload);
  }

  /** Edit capture fields while the lead is non-terminal (Req 3.6); 409 when terminal. */
  edit(id: number, payload: CreateLeadRequest): Observable<LeadDetail> {
    return this.api.put<LeadDetail>(`/api/leads/${id}`, payload);
  }

  /** Convert a lead into an order and mark it WON (Req 4); 409 if already terminal. */
  convert(id: number, payload: LeadConvertRequest): Observable<LeadDetail> {
    return this.api.post<LeadDetail>(`/api/leads/${id}/convert`, payload);
  }

  // --- Reports (Req 6) ----------------------------------------------------

  /** Leads-by-source report over an optional {@code from}/{@code to} window (Req 6.1). */
  reportBySource(from?: string | null, to?: string | null): Observable<BySourceReport> {
    return this.api.get<BySourceReport>('/api/leads/reports/by-source', {
      params: this.rangeParams(from, to),
    });
  }

  /** Conversion report (per source and per owner) over an optional window (Req 6.2). */
  reportConversion(from?: string | null, to?: string | null): Observable<ConversionReport> {
    return this.api.get<ConversionReport>('/api/leads/reports/conversion', {
      params: this.rangeParams(from, to),
    });
  }

  /** Pipeline snapshot: current active-lead counts per stage (Req 6.3). */
  reportPipeline(): Observable<PipelineReport> {
    return this.api.get<PipelineReport>('/api/leads/reports/pipeline');
  }

  /** Lost-reasons report over an optional window (Req 6.4). */
  reportLostReasons(from?: string | null, to?: string | null): Observable<LostReasonReport> {
    return this.api.get<LostReasonReport>('/api/leads/reports/lost-reasons', {
      params: this.rangeParams(from, to),
    });
  }

  private rangeParams(from?: string | null, to?: string | null): Record<string, string> {
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
