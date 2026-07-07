import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** A single cart line sent to the public checkout endpoint. */
export interface CheckoutItemRequest {
  productId: number;
  quantity: number;
}

/** Payload for {@code POST /api/checkout} (Req 3.1, 3.3-3.6; Phase D couponCode). */
export interface CheckoutRequest {
  customerName: string;
  customerMobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  items: CheckoutItemRequest[];
  /** Optional coupon code applied to the order (Phase D). */
  couponCode?: string;
}

/** Confirmation returned by the checkout endpoint (Req 3.7). */
export interface CheckoutResponse {
  id: number;
  orderCode: string;
}

/** Payload for {@code POST /api/checkout/validate-coupon} (Phase D). */
export interface ValidateCouponRequest {
  code: string;
  items: CheckoutItemRequest[];
}

/** Result of previewing a coupon against the cart (Phase D). */
export interface ValidateCouponResponse {
  valid: boolean;
  code: string;
  discountAmount: string;
  newTotal: string;
  freeShipping: boolean;
  message: string;
}

/**
 * Storefront checkout data access (Requirement 3).
 *
 * Thin wrapper over the shared {@link ApiClient} that posts the customer cart to
 * the public {@code POST /api/checkout} endpoint, which prices the cart from
 * product sale prices and creates the Order in {@code Pending_Admin_Approval}.
 */
@Injectable({ providedIn: 'root' })
export class CheckoutService {
  private readonly api = inject(ApiClient);

  /** Places a storefront order and returns its id + human order code (Req 3.6, 3.7). */
  placeOrder(request: CheckoutRequest): Observable<CheckoutResponse> {
    return this.api.post<CheckoutResponse>('/api/checkout', request);
  }

  /** Previews a coupon against the current cart (Phase D). */
  validateCoupon(request: ValidateCouponRequest): Observable<ValidateCouponResponse> {
    return this.api.post<ValidateCouponResponse>('/api/checkout/validate-coupon', request);
  }
}
