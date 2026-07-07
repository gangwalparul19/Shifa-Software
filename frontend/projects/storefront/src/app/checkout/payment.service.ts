import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** Body for {@code POST /api/payments/initiate} (Phase E). */
export interface InitiatePaymentRequest {
  orderId: number;
}

/**
 * Result of creating a payment session (Phase E). {@code keyId} is null for the
 * sandbox; {@code clientToken} is a sandbox-only opaque token the client echoes
 * back at confirm ({@code paymentId:signature}). For a real gateway the provider
 * widget supplies the payment id + signature instead and clientToken is null.
 */
export interface InitiatePaymentResponse {
  gateway: string;
  gatewayOrderId: string;
  amount: string;
  currency: string;
  keyId: string | null;
  paymentTxnId: number;
  clientToken: string | null;
}

/** Body for {@code POST /api/payments/confirm} (Phase E). */
export interface ConfirmPaymentRequest {
  orderId: number;
  gatewayOrderId: string;
  gatewayPaymentId: string;
  signature: string;
}

/** Result of confirming a payment (Phase E). */
export interface ConfirmPaymentResponse {
  paid: boolean;
  orderCode: string;
}

/**
 * Storefront online-payment data access (Phase E).
 *
 * <p>Thin wrapper over the shared {@link ApiClient} for the public
 * {@code /api/payments/initiate} and {@code /api/payments/confirm} endpoints.
 * Signature verification is entirely a server concern — the client only relays
 * what the gateway (sandbox or, later, Razorpay) produces.
 */
@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly api = inject(ApiClient);

  /** Creates a gateway session for an already-placed order. */
  initiate(request: InitiatePaymentRequest): Observable<InitiatePaymentResponse> {
    return this.api.post<InitiatePaymentResponse>('/api/payments/initiate', request);
  }

  /** Confirms a payment result; on success the server marks the order paid. */
  confirm(request: ConfirmPaymentRequest): Observable<ConfirmPaymentResponse> {
    return this.api.post<ConfirmPaymentResponse>('/api/payments/confirm', request);
  }
}
