import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Order, RejectReason } from 'core';
import { AuthenticatedBlobService } from '../shared/authenticated-blob.service';
import { ApprovalQueueItem, BulkApproveResult } from './approval.model';
import { PaymentScreenshot } from '../orders/orders.model';

/**
 * Data access for the admin order-approval workflow (Req 9).
 *
 * JSON calls go through the shared {@link ApiClient}; binary calls use the
 * shared authenticated blob transport and browser download helper so auth,
 * filename fallbacks and object-URL cleanup stay consistent.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

  /** The pending-approval queue with review details (Req 9.1). */
  queue(): Observable<ApprovalQueueItem[]> {
    return this.api.get<ApprovalQueueItem[]>('/api/admin/orders/approval-queue');
  }

  /**
   * Approve an order → Approved (Req 9.3). {@code deliveryMethod}, when
   * provided, sets/overrides the order's delivery partner as part of approving
   * (in-house-delivery feature) — omit to leave the order's current value
   * unchanged.
   */
  approve(orderId: number, deliveryMethod?: 'QUIKSHIPX' | 'IN_HOUSE'): Observable<Order> {
    return this.api.post<Order>(
      `/api/admin/orders/${orderId}/approve`,
      deliveryMethod ? { deliveryMethod } : {},
    );
  }

  /**
   * Bulk-approve the given orders in one call, reusing the existing partial-success
   * endpoint. Each eligible order is approved in its own transaction server-side;
   * ineligible ids come back in {@code skipped} with a reason.
   */
  bulkApprove(ids: number[]): Observable<BulkApproveResult> {
    return this.api.post<BulkApproveResult>('/api/admin/orders/bulk-approve', { ids });
  }

  /**
   * Verify an order's payment as genuine (payment-verification-gated approval),
   * reusing the Payment Verifier endpoint {@code POST /api/payments/{id}/verify}
   * (also permitted for ADMIN). Lets the admin clear the verification gate from
   * the approval queue so the order can then be approved.
   */
  verifyPayment(orderId: number, note?: string): Observable<Order> {
    return this.api.post<Order>(`/api/payments/${orderId}/verify`, note ? { note } : {});
  }

  /**
   * Reject an order with a mandatory reason → Rejected (Req 9.4). The optional
   * {@code category} records a concrete reason (Rate / Address-Pincode / Other,
   * rejection-status feature) alongside the free-text note.
   */
  reject(orderId: number, reason: string, category?: RejectReason): Observable<Order> {
    return this.api.post<Order>(`/api/admin/orders/${orderId}/reject`, { reason, category });
  }

  /** Fetch the PRIMARY payment screenshot as a Blob for inline rendering (Req 9.2, 21.2). */
  paymentScreenshot(orderId: number): Observable<Blob> {
    return this.blobs.get(`/api/orders/${orderId}/payment-screenshot`);
  }

  /** List every payment proof attached to the order, in upload order (V65). */
  paymentScreenshots(orderId: number): Observable<PaymentScreenshot[]> {
    return this.api.get<PaymentScreenshot[]>(`/api/orders/${orderId}/payment-screenshots`);
  }

  /** Fetch one specific payment proof as a Blob for inline rendering (V65). */
  paymentScreenshotById(orderId: number, screenshotId: number): Observable<Blob> {
    return this.blobs.get(`/api/orders/${orderId}/payment-screenshots/${screenshotId}`);
  }

  /**
   * Fetch the PDF invoice as a Blob from the authenticated, role-scoped endpoint
   * ({@code GET /api/orders/&#123;id&#125;/invoice}), so the reviewing admin can
   * open/download it, mirroring the payment-screenshot blob fetch.
   */
  invoice(orderId: number): Observable<Blob> {
    return this.blobs.get(`/api/orders/${orderId}/invoice`);
  }
}
