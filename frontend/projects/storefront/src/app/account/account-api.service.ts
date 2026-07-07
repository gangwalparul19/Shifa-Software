import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Money, OrderStatus, PaymentStatus, StockStatus } from 'core';

/** The current customer's profile ({@code GET /api/account/profile}). */
export interface CustomerProfile {
  id: number;
  username: string;
  fullName: string;
  email?: string | null;
  mobile?: string | null;
  role: string;
}

/** Profile update payload ({@code PUT /api/account/profile}). */
export interface ProfileUpdate {
  fullName: string;
  email?: string;
  mobile?: string;
}

/** A saved address in the customer's address book. */
export interface CustomerAddress {
  id: number;
  label?: string | null;
  fullName: string;
  mobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  isDefault: boolean;
  createdAt?: string;
}

/** Create/update payload for a saved address. */
export interface AddressInput {
  label?: string;
  fullName: string;
  mobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  makeDefault: boolean;
}

/** A single order in the customer's history ("My Orders"). */
export interface AccountOrderSummary {
  orderCode: string;
  createdAt?: string;
  totalAmount: Money;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  itemCount: number;
}

/** A line item within an account order detail (mirrors backend LineItemResponse). */
export interface AccountOrderLine {
  productId: number;
  productName: string;
  quantity: number;
  rate: Money;
  lineTotal: Money;
}

/** Full account order detail (mirrors backend OrderResponse). */
export interface AccountOrderDetail {
  id: number;
  orderCode: string;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  customerName: string;
  customerMobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  totalAmount: Money;
  items: AccountOrderLine[];
  createdAt?: string;
  awb?: string | null;
  courierName?: string | null;
  trackingUrl?: string | null;
  estimatedDelivery?: string | null;
}

/** A persisted wishlist entry (product summary). */
export interface WishlistProduct {
  productId: number;
  sku: string;
  name: string;
  mrp: Money;
  salePrice: Money;
  stockStatus: StockStatus;
  published: boolean;
  imageKey?: string | null;
}

/** A persisted server-side cart line for a signed-in customer. */
export interface CartProduct {
  productId: number;
  sku: string;
  name: string;
  salePrice: Money;
  mrp: Money;
  imageKey?: string | null;
  quantity: number;
}

/** A cart line sent when saving the server cart ({@code PUT /api/account/cart}). */
export interface CartLineInput {
  productId: number;
  quantity: number;
}

/**
 * The customer's public wishlist share link. {@code shareUrl} is a host-relative
 * storefront path ({@code /wishlist/shared/{token}}); the client turns it into an
 * absolute link with {@code window.location.origin}.
 */
export interface WishlistShare {
  token: string;
  shareUrl: string;
}

/**
 * Data access for the authenticated customer account area ({@code /api/account/**}).
 * A thin typed wrapper over the shared {@link ApiClient}; the bearer token is
 * attached by the core {@code authInterceptor}. Every endpoint is scoped
 * server-side to the calling customer.
 */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly api = inject(ApiClient);

  // --- Profile -----------------------------------------------------------
  getProfile(): Observable<CustomerProfile> {
    return this.api.get<CustomerProfile>('/api/account/profile');
  }

  updateProfile(update: ProfileUpdate): Observable<CustomerProfile> {
    return this.api.put<CustomerProfile>('/api/account/profile', update);
  }

  // --- Addresses ---------------------------------------------------------
  listAddresses(): Observable<CustomerAddress[]> {
    return this.api.get<CustomerAddress[]>('/api/account/addresses');
  }

  createAddress(input: AddressInput): Observable<CustomerAddress> {
    return this.api.post<CustomerAddress>('/api/account/addresses', input);
  }

  updateAddress(id: number, input: AddressInput): Observable<CustomerAddress> {
    return this.api.put<CustomerAddress>(`/api/account/addresses/${id}`, input);
  }

  deleteAddress(id: number): Observable<void> {
    return this.api.delete<void>(`/api/account/addresses/${id}`);
  }

  setDefaultAddress(id: number): Observable<CustomerAddress> {
    return this.api.post<CustomerAddress>(`/api/account/addresses/${id}/default`);
  }

  // --- Orders ------------------------------------------------------------
  listOrders(): Observable<AccountOrderSummary[]> {
    return this.api.get<AccountOrderSummary[]>('/api/account/orders');
  }

  /** Full detail for one of the customer's own orders (used for reorder). */
  getOrder(orderCode: string): Observable<AccountOrderDetail> {
    return this.api.get<AccountOrderDetail>(
      `/api/account/orders/${encodeURIComponent(orderCode)}`,
    );
  }

  /**
   * Absolute URL of the public PDF invoice for an order code
   * ({@code GET /api/track/{code}/invoice}). Public, so it can be opened
   * directly in a new tab for download.
   */
  invoiceUrl(orderCode: string): string {
    return this.api.url(`/api/track/${encodeURIComponent(orderCode)}/invoice`);
  }

  // --- Wishlist ----------------------------------------------------------
  listWishlist(): Observable<WishlistProduct[]> {
    return this.api.get<WishlistProduct[]>('/api/account/wishlist');
  }

  addWishlist(productId: number): Observable<void> {
    return this.api.post<void>(`/api/account/wishlist/${productId}`);
  }

  removeWishlist(productId: number): Observable<void> {
    return this.api.delete<void>(`/api/account/wishlist/${productId}`);
  }

  // --- Cart (server-side cart for signed-in customers) -------------------
  /** The customer's saved server cart ({@code GET /api/account/cart}). */
  getCart(): Observable<CartProduct[]> {
    return this.api.get<CartProduct[]>('/api/account/cart');
  }

  /**
   * Replaces the customer's server cart with {@code items} and returns the saved
   * cart ({@code PUT /api/account/cart}); the server drops unknown products and
   * out-of-range quantities.
   */
  saveCart(items: CartLineInput[]): Observable<CartProduct[]> {
    return this.api.put<CartProduct[]>('/api/account/cart', { items });
  }

  /** Empties the customer's server cart ({@code DELETE /api/account/cart}). */
  clearCart(): Observable<void> {
    return this.api.delete<void>('/api/account/cart');
  }

  // --- Wishlist sharing --------------------------------------------------
  /** Creates or returns the current public share link (idempotent server-side). */
  createWishlistShare(): Observable<WishlistShare> {
    return this.api.post<WishlistShare>('/api/account/wishlist/share');
  }

  /** Revokes the current public share link (idempotent). */
  revokeWishlistShare(): Observable<void> {
    return this.api.delete<void>('/api/account/wishlist/share');
  }

  /**
   * Public, read-only shared wishlist by token ({@code GET /api/wishlist/shared/{token}}).
   * Needs no authentication — anyone with the link can view the product summaries.
   */
  getSharedWishlist(token: string): Observable<WishlistProduct[]> {
    return this.api.get<WishlistProduct[]>(
      `/api/wishlist/shared/${encodeURIComponent(token)}`,
    );
  }
}
