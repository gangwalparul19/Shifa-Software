import { Money, OrderSource, OrderStatus, PaymentStatus } from 'core';

/**
 * A single line in a new salesperson order (mirrors the backend
 * {@code LineItemRequest}). {@code rate} is optional: when omitted the backend
 * uses the product's default sale price; when supplied it overrides that line.
 */
export interface CreateOrderLineItem {
  productId: number;
  quantity: number;
  rate?: number | null;
}

/**
 * The lead's origin channel captured at order entry (mirrors the backend
 * {@code LeadSource} enum, Req 4.1). Distinct from {@link OrderSource}
 * provenance — this records how the customer reached the salesperson.
 */
export type LeadSource =
  | 'WHATSAPP'
  | 'INSTAGRAM'
  | 'FACEBOOK'
  | 'GOOGLE'
  | 'OFFLINE'
  | 'OTHER';

/** The selectable lead-source options for the New Order picker (Req 4.1). */
export const LEAD_SOURCE_OPTIONS: { value: LeadSource; label: string }[] = [
  { value: 'WHATSAPP', label: 'WhatsApp' },
  { value: 'INSTAGRAM', label: 'Instagram' },
  { value: 'FACEBOOK', label: 'Facebook' },
  { value: 'GOOGLE', label: 'Google' },
  { value: 'OFFLINE', label: 'Offline' },
  { value: 'OTHER', label: 'Other' },
];

/**
 * Salesperson order-entry payload posted to {@code POST /api/orders} (mirrors
 * the backend {@code CreateOrderRequest}). {@code paymentScreenshotKey} is the
 * storage key returned by the two-step screenshot upload and is required by the
 * server when {@code amountReceived > 0}.
 *
 * <p>Order entry also captures the lead's origin (Req 4.1–4.5): a required
 * {@code leadSource}, an optional {@code leadSourceNote} (≤200 chars, only for
 * {@code OTHER}), and an optional {@code customerEmail} for milestone emails.
 */
export interface CreateOrderRequest {
  customerName: string;
  customerMobile: string;
  customerEmail?: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  items: CreateOrderLineItem[];
  amountReceived: number;
  paymentScreenshotKey?: string;
  leadSource: LeadSource;
  leadSourceNote?: string;
}

/**
 * Result of {@code POST /api/orders/payment-screenshots} (mirrors the backend
 * {@code ScreenshotUploadResponse}) — the storage {@code key} to attach to a
 * subsequent order.
 */
export interface ScreenshotUploadResponse {
  key: string;
}

/**
 * An online-payment transaction for an order (Phase E), returned by
 * {@code GET /api/orders/{id}/payments}. Mirrors the backend
 * {@code PaymentTransactionResponse}.
 */
export interface PaymentTransaction {
  id: number;
  orderId: number;
  gateway: string;
  gatewayOrderId: string;
  gatewayPaymentId?: string;
  amount: Money;
  status: 'CREATED' | 'PAID' | 'FAILED';
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Compact order row returned by the search endpoint
 * {@code GET /api/orders?search=} (Req 22.1). Mirrors the backend
 * {@code OrderSummaryResponse}.
 */
export interface OrderSummary {
  id: number;
  orderCode: string;
  customerName: string;
  customerMobile: string;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  totalAmount: Money;
  codAmount: Money;
  createdAt?: string;
}

/** A single line within a full order detail. */
export interface OrderDetailLine {
  productId?: number;
  productName: string;
  /**
   * HSN code snapshotted at order time (Wave 3, Feature 2); null on legacy
   * rows or products without an HSN.
   */
  hsnCode?: string | null;
  /**
   * GST rate percent snapshotted at order time (DECIMAL(5,2) as a string, e.g.
   * "12.00"); null when the product had no per-line rate.
   */
  gstRate?: string | null;
  quantity: number;
  rate: Money;
  lineTotal: Money;
}

/**
 * Full order detail returned by {@code GET /api/orders/{id}} (Req 21.1). Mirrors
 * the backend {@code OrderResponse}. The AWB / courier tracking fields are
 * optional and only rendered when the backend supplies them.
 */
export interface OrderDetail {
  id: number;
  orderCode: string;
  source: OrderSource;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  customerName: string;
  customerMobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  totalAmount: Money;
  amountReceived: Money;
  remainingAmount: Money;
  codAmount: Money;
  customerOutstanding?: Money;
  paymentScreenshotAvailable: boolean;
  items: OrderDetailLine[];
  createdAt?: string;
  /** Air Waybill number, when a courier has been assigned. */
  awb?: string;
  /** Courier company name, when available. */
  courierName?: string;
  /** Deep link to the courier's tracking page, when available. */
  trackingUrl?: string;
  /** Estimated delivery date (ISO yyyy-MM-dd), when available. */
  estimatedDelivery?: string;
}
