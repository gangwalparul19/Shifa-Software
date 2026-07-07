import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import {
  ApproveReturnRequest,
  CreateReturnRequest,
  RefundReturnRequest,
  RejectReturnRequest,
  ReturnResponse,
  ReturnStatus,
} from './returns.model';

/** Filters + paging for the admin returns / refunds listing (server-side). */
export interface ReturnPageQuery {
  status?: ReturnStatus | string | null;
  q?: string | null;
  /** Inclusive lower bound, yyyy-MM-dd. */
  from?: string | null;
  /** Inclusive upper bound, yyyy-MM-dd. */
  to?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/**
 * Data access for admin Returns / Refunds ({@code /api/admin/returns}).
 * Reads are ADMIN + ACCOUNTANT; mutations (create/approve/reject) are ADMIN and
 * refund is ADMIN + ACCOUNTANT — the backend enforces this, the UI mirrors it.
 * Calls go through the shared {@link ApiClient} (bearer token via interceptor).
 */
@Injectable({ providedIn: 'root' })
export class ReturnsService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered, sorted returns. */
  page(query: ReturnPageQuery): Observable<PageResponse<ReturnResponse>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    if (query.status) {
      params['status'] = query.status;
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
    return this.api.get<PageResponse<ReturnResponse>>('/api/admin/returns', { params });
  }

  /** Create a new return request (ADMIN). */
  create(request: CreateReturnRequest): Observable<ReturnResponse> {
    return this.api.post<ReturnResponse>('/api/admin/returns', request);
  }

  /** Approve a return, optionally restocking and setting a refund amount (ADMIN). */
  approve(id: number, request: ApproveReturnRequest): Observable<ReturnResponse> {
    return this.api.post<ReturnResponse>(`/api/admin/returns/${id}/approve`, request);
  }

  /** Reject a return with optional notes (ADMIN). */
  reject(id: number, request: RejectReturnRequest): Observable<ReturnResponse> {
    return this.api.post<ReturnResponse>(`/api/admin/returns/${id}/reject`, request);
  }

  /** Mark a return refunded with the refunded amount (ADMIN + ACCOUNTANT). */
  refund(id: number, request: RefundReturnRequest): Observable<ReturnResponse> {
    return this.api.post<ReturnResponse>(`/api/admin/returns/${id}/refund`, request);
  }

  /** Existing returns for a given order (used from the Orders detail view). */
  byOrder(orderId: number): Observable<ReturnResponse[]> {
    return this.api.get<ReturnResponse[]>(`/api/admin/returns/by-order/${orderId}`);
  }
}
