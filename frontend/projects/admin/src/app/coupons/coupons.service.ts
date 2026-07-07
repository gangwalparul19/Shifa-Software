import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** The kind of discount a coupon grants (mirrors the backend CouponType). */
export type CouponType = 'PERCENT' | 'FLAT' | 'FREE_SHIPPING';

/** Admin view of a coupon (mirrors the backend CouponResponse, Phase D). */
export interface Coupon {
  id: number;
  code: string;
  description?: string | null;
  type: CouponType;
  value: string;
  minCartAmount?: string | null;
  maxDiscountAmount?: string | null;
  active: boolean;
  startsAt?: string | null;
  endsAt?: string | null;
  usageLimit?: number | null;
  usedCount: number;
  perCustomerLimit?: number | null;
  createdAt?: string | null;
}

/** Create/update payload (mirrors the backend CouponRequest, Phase D). */
export interface CouponRequest {
  code: string;
  description?: string | null;
  type: CouponType;
  value: string;
  minCartAmount?: string | null;
  maxDiscountAmount?: string | null;
  active: boolean;
  startsAt?: string | null;
  endsAt?: string | null;
  usageLimit?: number | null;
  perCustomerLimit?: number | null;
}

/**
 * Data access for admin coupon management ({@code /api/admin/coupons}, Phase D).
 * Calls go through the shared {@link ApiClient}; the auth interceptor attaches
 * the bearer token and the backend enforces the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class CouponsService {
  private readonly api = inject(ApiClient);

  /** All coupons, newest first, for the management grid. */
  list(): Observable<Coupon[]> {
    return this.api.get<Coupon[]>('/api/admin/coupons');
  }

  /** Create a coupon; a duplicate code is rejected with a 409. */
  create(request: CouponRequest): Observable<Coupon> {
    return this.api.post<Coupon>('/api/admin/coupons', request);
  }

  /** Update a coupon. */
  update(id: number, request: CouponRequest): Observable<Coupon> {
    return this.api.put<Coupon>(`/api/admin/coupons/${id}`, request);
  }

  /** Activate or deactivate a coupon (soft toggle). */
  setActive(id: number, active: boolean): Observable<Coupon> {
    return this.api.put<Coupon>(`/api/admin/coupons/${id}/active`, null, { params: { active } });
  }
}
