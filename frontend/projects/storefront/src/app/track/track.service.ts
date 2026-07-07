import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, OrderStatus } from 'core';

/**
 * Public order-tracking response returned by {@code GET /api/track/{orderCode}}
 * (Req 13.4). Fields beyond the status are populated once a courier is assigned.
 */
export interface OrderTracking {
  orderCode: string;
  orderStatus: OrderStatus | string;
  awb?: string | null;
  courierName?: string | null;
  trackingUrl?: string | null;
  estimatedDelivery?: string | null;
  /** COD amount due, when the order is Cash on Delivery (fixed-scale money string). */
  codAmount?: string | number | null;
  /** Current payment status, when exposed by the tracking endpoint. */
  paymentStatus?: string | null;
}

/**
 * Storefront order-tracking data access (Requirement 13.4).
 *
 * Thin wrapper over the shared {@link ApiClient} that reads the public tracking
 * endpoint by human order code. The endpoint returns the current Order_Status,
 * AWB, courier name, and courier tracking link.
 */
@Injectable({ providedIn: 'root' })
export class TrackService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** Looks up tracking details for an order by its human order code. */
  track(orderCode: string): Observable<OrderTracking> {
    return this.api.get<OrderTracking>(`/api/track/${encodeURIComponent(orderCode.trim())}`);
  }

  /**
   * Fetches the PDF invoice for an order by its code as a {@code Blob} from the
   * public endpoint {@code GET /api/track/{orderCode}/invoice} (no auth, mirrors
   * the public tracking lookup), so the storefront customer can download/open
   * their own invoice.
   */
  invoice(orderCode: string): Observable<Blob> {
    return this.http.get(
      this.api.url(`/api/track/${encodeURIComponent(orderCode.trim())}/invoice`),
      { responseType: 'blob' },
    );
  }
}
