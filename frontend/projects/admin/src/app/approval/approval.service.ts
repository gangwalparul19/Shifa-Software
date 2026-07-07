import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Order } from 'core';
import { ApprovalQueueItem } from './approval.model';

/**
 * Data access for the admin order-approval workflow (Req 9).
 *
 * <p>JSON calls go through the shared {@link ApiClient} (the auth interceptor
 * attaches the bearer token). The payment screenshot is a binary download, so
 * it is fetched with {@link HttpClient} as a {@code Blob} against the same
 * authenticated endpoint ({@code GET /api/orders/&#123;id&#125;/payment-screenshot},
 * ACCOUNTANT/ADMIN, Req 9.2, 21.2).
 */
@Injectable({ providedIn: 'root' })
export class ApprovalService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The pending-approval queue with review details (Req 9.1). */
  queue(): Observable<ApprovalQueueItem[]> {
    return this.api.get<ApprovalQueueItem[]>('/api/admin/orders/approval-queue');
  }

  /** Approve an order → Approved (Req 9.3). */
  approve(orderId: number): Observable<Order> {
    return this.api.post<Order>(`/api/admin/orders/${orderId}/approve`);
  }

  /** Reject an order with a mandatory reason → Rejected (Req 9.4). */
  reject(orderId: number, reason: string): Observable<Order> {
    return this.api.post<Order>(`/api/admin/orders/${orderId}/reject`, { reason });
  }

  /** Fetch the payment screenshot as a Blob for inline rendering (Req 9.2, 21.2). */
  paymentScreenshot(orderId: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/orders/${orderId}/payment-screenshot`), {
      responseType: 'blob',
    });
  }

  /**
   * Fetch the PDF invoice as a Blob from the authenticated, role-scoped endpoint
   * ({@code GET /api/orders/&#123;id&#125;/invoice}), so the reviewing admin can
   * open/download it, mirroring the payment-screenshot blob fetch.
   */
  invoice(orderId: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/orders/${orderId}/invoice`), {
      responseType: 'blob',
    });
  }
}
