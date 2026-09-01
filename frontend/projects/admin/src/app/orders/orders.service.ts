import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, OrderStatus, PageResponse, PaymentStatus } from 'core';
import {
  CreateOrderRequest,
  CustomerPrefillResponse,
  DuplicateCheckResponse,
  OrderDetail,
  OrderSummary,
  QuikShipPublishAck,
  QuikShipShipment,
  QuikShipTracking,
  ScreenshotUploadResponse,
} from './orders.model';

/** Filters + paging for the admin all-orders page (server-side, Wave 2). */
export interface OrderPageQuery {
  q?: string | null;
  status?: OrderStatus | string | null;
  /** Coarse lifecycle group key (e.g. PENDING_APPROVAL); expands server-side to a status set. */
  statusGroup?: string | null;
  paymentStatus?: PaymentStatus | string | null;
  /** Inclusive lower bound, yyyy-MM-dd. */
  from?: string | null;
  /** Inclusive upper bound, yyyy-MM-dd. */
  to?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/** One skipped row in a bulk operation, with the reason it was skipped. */
export interface BulkSkip {
  id: number;
  reason: string;
}

/** Partial-result summary returned by the bulk approve / mark-packed endpoints. */
export interface BulkResult {
  succeeded: number[];
  skipped: BulkSkip[];
}

/**
 * Data access for the admin all-orders view (Req 21, 22).
 *
 * <p>JSON calls go through the shared {@link ApiClient} (the auth interceptor
 * attaches the bearer token; the backend scopes salespeople to their own
 * orders). The payment screenshot is a binary download, fetched with
 * {@link HttpClient} as a {@code Blob} against the authenticated endpoint
 * ({@code GET /api/orders/{id}/payment-screenshot}, ACCOUNTANT/ADMIN, Req 21.2).
 */
@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /**
   * Punch a new salesperson/admin order via {@code POST /api/orders}
   * (SALESPERSON + ADMIN, Req 7). Returns the created {@link OrderDetail}
   * (the backend {@code OrderResponse}) carrying the generated {@code orderCode}
   * and computed totals; the order enters {@code Pending_Admin_Approval}.
   */
  createOrder(payload: CreateOrderRequest): Observable<OrderDetail> {
    return this.api.post<OrderDetail>('/api/orders', payload);
  }

  /**
   * Upload a payment screenshot (step one of the two-step flow) via
   * {@code POST /api/orders/payment-screenshots} as multipart form field
   * {@code file}. Returns the storage {@code key} to attach as
   * {@code paymentScreenshotKey} on a subsequent {@link createOrder} call.
   *
   * <p>The body is a {@link FormData} instance, so Angular's {@link HttpClient}
   * sets the multipart {@code Content-Type} (with boundary) itself — we must not
   * set it manually. The auth interceptor still attaches the bearer token.
   */
  uploadPaymentScreenshot(file: File): Observable<ScreenshotUploadResponse> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<ScreenshotUploadResponse>('/api/orders/payment-screenshots', form);
  }

  /**
   * Whether prior orders exist for a customer mobile number
   * ({@code GET /api/orders/duplicate-check?mobile=}, SALESPERSON + ADMIN,
   * Req 22.2). Powers the repeat-customer hint on the New Order form.
   */
  duplicateCheck(mobile: string): Observable<DuplicateCheckResponse> {
    return this.api.get<DuplicateCheckResponse>('/api/orders/duplicate-check', {
      params: { mobile },
    });
  }

  /**
   * Customer + shipping details from the customer's most recent order
   * ({@code GET /api/orders/last-by-mobile?mobile=}, SALESPERSON + ADMIN), to
   * pre-fill the New Order form when a known mobile is entered.
   */
  lastCustomerByMobile(mobile: string): Observable<CustomerPrefillResponse> {
    return this.api.get<CustomerPrefillResponse>('/api/orders/last-by-mobile', {
      params: { mobile },
    });
  }

  /** Search orders by name / mobile / order code / AWB, role-scoped (Req 22.1). */
  search(term?: string): Observable<OrderSummary[]> {
    const params = term && term.trim() ? { search: term.trim() } : undefined;
    return this.api.get<OrderSummary[]>('/api/orders', params ? { params } : undefined);
  }

  /**
   * Server-side paginated, filtered, sorted orders (Wave 2). Backed by
   * {@code GET /api/admin/orders}. Empty/nullish filters are omitted so the
   * backend applies its defaults.
   */
  page(query: OrderPageQuery): Observable<PageResponse<OrderSummary>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (query.status) {
      params['status'] = query.status;
    }
    if (query.statusGroup) {
      params['statusGroup'] = query.statusGroup;
    }
    if (query.paymentStatus) {
      params['paymentStatus'] = query.paymentStatus;
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
    return this.api.get<PageResponse<OrderSummary>>('/api/admin/orders', { params });
  }

  /** Bulk-approve the given orders; returns a partial-result summary. */
  bulkApprove(ids: number[]): Observable<BulkResult> {
    return this.api.post<BulkResult>('/api/admin/orders/bulk-approve', { ids });
  }

  /** Bulk-mark the given orders as packed; returns a partial-result summary. */
  bulkMarkPacked(ids: number[]): Observable<BulkResult> {
    return this.api.post<BulkResult>('/api/admin/orders/bulk-mark-packed', { ids });
  }

  /**
   * Generate a single merged PDF of shipping labels for the given orders. The
   * response is a binary {@code application/pdf}; fetched with {@link HttpClient}
   * as a {@code Blob} (the auth interceptor attaches the bearer token).
   */
  bulkLabels(ids: number[]): Observable<Blob> {
    return this.http.post(this.api.url('/api/admin/orders/bulk-labels'), { ids }, {
      responseType: 'blob',
    });
  }

  /** Full order detail with line items and payment tracking (Req 21.1). */
  detail(id: number): Observable<OrderDetail> {
    return this.api.get<OrderDetail>(`/api/orders/${id}`);
  }

  /** Fetch the payment screenshot as a Blob for inline rendering (Req 21.2). */
  paymentScreenshot(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/orders/${id}/payment-screenshot`), {
      responseType: 'blob',
    });
  }

  /**
   * Fetch the PDF invoice as a Blob from the authenticated, role-scoped endpoint
   * ({@code GET /api/orders/{id}/invoice}). Fetched with {@link HttpClient} as a
   * {@code Blob} (the auth interceptor attaches the bearer token) so the PDF can
   * be opened/downloaded, mirroring the payment-screenshot blob fetch.
   */
  invoice(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/orders/${id}/invoice`), {
      responseType: 'blob',
    });
  }

  /**
   * Fetch the internal packing label PDF (Code128 barcode + order/customer
   * details) as a Blob so it can be opened/printed
   * ({@code GET /api/admin/labels/internal/{id}}, ADMIN + PACKING_USER).
   */
  label(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/admin/labels/internal/${id}`), {
      responseType: 'blob',
    });
  }

  /**
   * The QuikShipX shipment mirror for an order
   * ({@code GET /api/orders/{id}/quikshipx}, ADMIN). 404 when not yet published.
   */
  quikShipShipment(id: number): Observable<QuikShipShipment> {
    return this.api.get<QuikShipShipment>(`/api/orders/${id}/quikshipx`);
  }

  /**
   * (Re)queue an order for publication to QuikShipX
   * ({@code POST /api/orders/{id}/quikshipx/publish}, ADMIN). Idempotent.
   */
  quikShipPublish(id: number): Observable<QuikShipPublishAck> {
    return this.api.post<QuikShipPublishAck>(`/api/orders/${id}/quikshipx/publish`, {});
  }

  /**
   * Live QuikShipX tracking for an order — current status + scan timeline
   * ({@code GET /api/orders/{id}/quikshipx/track}). Also refreshes the internal
   * order status server-side (idempotent). 404 when not yet published.
   */
  quikShipTrack(id: number): Observable<QuikShipTracking> {
    return this.api.get<QuikShipTracking>(`/api/orders/${id}/quikshipx/track`);
  }
}
