import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AuthenticatedBlobService } from '../shared/authenticated-blob.service';
import { PaymentQueueRow } from './payments.model';
import { PaymentScreenshot } from '../orders/orders.model';

/**
 * Data access for the Payment Verifier dashboard ({@code /api/payments},
 * shared {@link ApiClient}; binary screenshot calls use the shared authenticated
 * blob transport so the auth interceptor attaches the bearer token consistently.
 */
@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly api = inject(ApiClient);
  private readonly blobs = inject(AuthenticatedBlobService);

  /** Prepaid payments awaiting verification, oldest first. */
  queue(): Observable<PaymentQueueRow[]> {
    return this.api.get<PaymentQueueRow[]>('/api/payments/queue');
  }

  /** Mark a payment as genuine (optional note). */
  verify(id: number, note?: string): Observable<unknown> {
    return this.api.post('/api/payments/' + id + '/verify', { note: note ?? '' });
  }

  /** Flag a payment as not genuine / mismatched (optional note). */
  reject(id: number, note?: string): Observable<unknown> {
    return this.api.post('/api/payments/' + id + '/reject', { note: note ?? '' });
  }

  /** Fetch the order's PRIMARY payment screenshot as a Blob for inline viewing. */
  screenshot(orderId: number): Observable<Blob> {
    return this.blobs.get(`/api/orders/${orderId}/payment-screenshot`);
  }

  /** List every payment proof attached to the order, in upload order (V65). */
  screenshots(orderId: number): Observable<PaymentScreenshot[]> {
    return this.api.get<PaymentScreenshot[]>(`/api/orders/${orderId}/payment-screenshots`);
  }

  /** Fetch one specific payment proof as a Blob for inline viewing (V65). */
  screenshotById(orderId: number, screenshotId: number): Observable<Blob> {
    return this.blobs.get(`/api/orders/${orderId}/payment-screenshots/${screenshotId}`);
  }
}
