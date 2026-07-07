import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import {
  CreatePurchaseOrderRequest,
  PurchaseOrderResponse,
  PurchaseOrderStatus,
  PurchaseOrderSummaryResponse,
  ReceivePurchaseOrderRequest,
} from './purchase-orders.model';

/** Filters + paging for the admin purchase-orders listing (server-side). */
export interface PurchaseOrderPageQuery {
  status?: PurchaseOrderStatus | string | null;
  supplierId?: number | null;
  q?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/**
 * Data access for admin Purchase Orders ({@code /api/admin/purchase-orders},
 * ADMIN only). Calls go through the shared {@link ApiClient}; the auth
 * interceptor attaches the bearer token and the backend enforces the role.
 */
@Injectable({ providedIn: 'root' })
export class PurchaseOrdersService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered, sorted purchase orders. */
  page(query: PurchaseOrderPageQuery): Observable<PageResponse<PurchaseOrderSummaryResponse>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    if (query.status) {
      params['status'] = query.status;
    }
    if (query.supplierId != null) {
      params['supplierId'] = query.supplierId;
    }
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<PurchaseOrderSummaryResponse>>(
      '/api/admin/purchase-orders',
      { params },
    );
  }

  /** A single purchase order with its line items. */
  get(id: number): Observable<PurchaseOrderResponse> {
    return this.api.get<PurchaseOrderResponse>(`/api/admin/purchase-orders/${id}`);
  }

  /** Create a new purchase order (created directly as ORDERED). */
  create(request: CreatePurchaseOrderRequest): Observable<PurchaseOrderResponse> {
    return this.api.post<PurchaseOrderResponse>('/api/admin/purchase-orders', request);
  }

  /** Receive stock against a purchase order's line items. */
  receive(id: number, request: ReceivePurchaseOrderRequest): Observable<PurchaseOrderResponse> {
    return this.api.post<PurchaseOrderResponse>(
      `/api/admin/purchase-orders/${id}/receive`,
      request,
    );
  }

  /** Cancel a purchase order (only when DRAFT or ORDERED). */
  cancel(id: number): Observable<PurchaseOrderResponse> {
    return this.api.post<PurchaseOrderResponse>(`/api/admin/purchase-orders/${id}/cancel`, {});
  }
}
