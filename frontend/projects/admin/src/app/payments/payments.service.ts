import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { PaymentQueueRow } from './payments.model';

/**
 * Data access for the Payment Verifier dashboard ({@code /api/payments},
 * PAYMENT_VERIFIER + ADMIN, product-audit §4.4). JSON calls go through the
 * shared {@link ApiClient}; the screenshot is fetched as a Blob with
 * {@link HttpClient} so the auth interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

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

  /** Fetch the order's payment screenshot as a Blob for inline viewing. */
  screenshot(orderId: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/orders/${orderId}/payment-screenshot`), {
      responseType: 'blob',
    });
  }
}
