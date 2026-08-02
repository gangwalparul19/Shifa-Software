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
  /** Optional alternate contact number (10 digits) for failed-delivery follow-up. */
  alternateMobile?: string;
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
  /** Optional free-text order note captured at order entry (≤1000 chars). */
  notes?: string;
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
 * Whether prior orders exist for a customer mobile number, from
 * {@code GET /api/orders/duplicate-check?mobile=} (mirrors the backend
 * {@code DuplicateCheckResponse}). Powers the repeat-customer hint on the New
 * Order form.
 */
export interface DuplicateCheckResponse {
  mobile: string;
  hasPriorOrders: boolean;
  priorOrderCount: number;
}

/**
 * Customer + shipping details from a customer's most recent order, from
 * {@code GET /api/orders/last-by-mobile?mobile=} (mirrors the backend
 * {@code CustomerPrefillResponse}). Used to pre-fill the New Order form when a
 * known mobile is entered; `found=false` means no prior order. All values are
 * suggestions the salesperson can override.
 */
export interface CustomerPrefillResponse {
  found: boolean;
  customerName: string | null;
  customerEmail: string | null;
  alternateMobile: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  leadSource: string | null;
  leadSourceNote: string | null;
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
  /**
   * Storage key of the line product's primary (first published) image, or
   * null/absent when the product has no published image. Populated only on the
   * order-detail response; resolved to a URL via `resolveImageUrl` with a
   * placeholder fallback.
   */
  imageKey?: string | null;
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
  /** Optional alternate contact number captured at order entry. */
  alternateMobile?: string | null;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  totalAmount: Money;
  amountReceived: Money;
  remainingAmount: Money;
  codAmount: Money;
  customerOutstanding?: Money;
  /**
   * Order-level discount applied (Money decimal string; defaults to "0.00").
   * The order-detail totals show a "- ₹X" discount line only when this is > 0.
   */
  discountAmount?: Money;
  paymentScreenshotAvailable: boolean;
  /** Optional free-text order note captured at order entry. */
  notes?: string | null;
  /** Who the order was handed to at handover (product-audit §4.3), when captured. */
  handoverName?: string | null;
  /** Number of boxes the order ships in (product-audit §4.2); defaults to 1. */
  packageCount?: number;
  /**
   * Payment authenticity-verification state (product-audit §4.4): PENDING /
   * VERIFIED / REJECTED for prepaid orders; null for pure COD (nothing to verify).
   */
  paymentVerificationStatus?: 'PENDING' | 'VERIFIED' | 'REJECTED' | null;
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

/**
 * Result of the admin "Send to QuikShipX now" action
 * ({@code POST /api/admin/orders/{id}/publish-quikshipx}). Mirrors the backend
 * {@code PublishNowResponse}.
 *
 * <p>{@link outcome} is either {@code PUBLISHED}, a skip reason (e.g.
 * {@code CREDENTIALS_MISSING}, {@code ALREADY_PUBLISHED},
 * {@code SHIPMENT_DEFAULTS_INCOMPLETE}), or {@code PUBLICATION_FAILED} when QuikShipX
 * rejected the call. The UI shows {@link detail} to the admin either way.
 */
export interface PublishNowResponse {
  published: boolean;
  outcome: string;
  detail?: string | null;
  orderReference?: string | null;
  awb?: string | null;
  shipmentId?: string | null;
  test: boolean;
}

/**
 * An order's QuikShipX shipment ({@code GET /api/orders/{id}/shipment}). Mirrors the backend
 * {@code ShipmentResponse}. Present only once the order has been published to QuikShipX; the
 * endpoint returns 404 (mapped to {@code null} in the service) when it has not.
 */
export interface ShipmentInfo {
  orderReference: string;
  quikshipxShipmentId?: string | null;
  /** QuikShipX's own order id (their {@code order_id}, e.g. "177286") for portal tracking. */
  quikshipxOrderId?: string | null;
  awb?: string | null;
  courierName?: string | null;
  trackingUrl?: string | null;
  labelUrl?: string | null;
  /** QuikShipX's own status for the order (e.g. "Pending", "Label Printed", "Delivered"). */
  lastStatusToken?: string | null;
  /** When that QuikShipX status occurred (ISO). */
  lastStatusAt?: string | null;
  test: boolean;
  statusMirroringActive: boolean;
  labelFromPortal: boolean;
}
