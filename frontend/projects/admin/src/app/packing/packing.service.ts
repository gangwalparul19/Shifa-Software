import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { OrderDetail } from '../orders/orders.model';
import { PackingQueue, PackingScanResponse } from './packing.model';

/**
 * Data access for the packing barcode-scan workflow (Req 11).
 *
 * <p>Posts the scanned barcode to {@code POST /api/packing/scan} through the
 * shared {@link ApiClient} (the auth interceptor attaches the bearer token). A
 * success resolves with the packed order; the not-recognized (404) and
 * wrong-status (409) outcomes surface as {@code HttpErrorResponse}s carrying the
 * standard {@code ApiError} envelope, which the component interprets.
 */
@Injectable({ providedIn: 'root' })
export class PackingService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The packing work queues (awaiting packing / handover / dispatch), oldest-first. */
  queue(): Observable<PackingQueue> {
    return this.api.get<PackingQueue>('/api/packing/queue');
  }

  /**
   * Fetch the internal label PDF (barcode + order/customer details) for an order
   * as a Blob so it can be opened/printed. Uses {@link HttpClient} directly so
   * the auth interceptor attaches the bearer token
   * ({@code GET /api/admin/labels/internal/{id}}, ADMIN + PACKING_USER).
   */
  label(orderId: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/admin/labels/internal/${orderId}`), {
      responseType: 'blob',
    });
  }

  /**
   * Fetch a single combined PDF with one internal-label block per requested order
   * (product-audit §4.1 — multi-label print). Posts to the existing
   * {@code POST /api/admin/labels/internal/bulk} (ADMIN + PACKING_USER).
   */
  bulkLabels(orderIds: number[]): Observable<Blob> {
    return this.http.post(this.api.url('/api/admin/labels/internal/bulk'), { orderIds }, {
      responseType: 'blob',
    });
  }

  /**
   * Set how many boxes an order ships in (product-audit §4.2 — multi-pack). The
   * label print then produces one copy per box. Returns the updated order.
   */
  setPackages(id: number, packageCount: number): Observable<OrderDetail> {
    return this.api.post<OrderDetail>(`/api/packing/${id}/packages`, { packageCount });
  }

  /** Scan a barcode to mark the matching order Packed (Req 11.1). */
  scan(barcode: string): Observable<PackingScanResponse> {
    return this.api.post<PackingScanResponse>('/api/packing/scan', { barcode });
  }

  /**
   * Hand a packed order over to the delivery courier
   * ({@code PACKED → HANDED_TO_DELIVERY}, Req 9.2–9.4). Returns the updated
   * order; a non-{@code PACKED} order surfaces as a 409 {@code HttpErrorResponse}.
   */
  handover(id: number, handoverName?: string, handoverPhone?: string): Observable<OrderDetail> {
    const body: { handoverName?: string; handoverPhone?: string } = {};
    if (handoverName && handoverName.trim()) {
      body.handoverName = handoverName.trim();
    }
    if (handoverPhone && handoverPhone.trim()) {
      body.handoverPhone = handoverPhone.trim();
    }
    return this.api.post<OrderDetail>(`/api/packing/${id}/handover`, body);
  }

  /**
   * Dispatch a handed-over order by enqueuing courier assignment (Req 10.1).
   * Returns the order; a non-{@code HANDED_TO_DELIVERY} order surfaces as a 409.
   */
  dispatch(id: number): Observable<OrderDetail> {
    return this.api.post<OrderDetail>(`/api/packing/${id}/dispatch`);
  }
}
