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
  /**
   * Optional buyer GSTIN (gst-filing-compliance Req 1). When present it must be a
   * valid 15-char GSTIN and classifies the sale as B2B for GSTR-1.
   */
  buyerGstin?: string;
  /** Optional order-level discount kind (product-catalog-pricing-gst Req 6). */
  discountType?: OrderDiscountType;
  /** The raw discount value entered (rupee amount for FLAT, percent for PERCENT). */
  discountValue?: number;
}

/** The kind of order-level discount (mirrors the backend). */
export type OrderDiscountType = 'FLAT' | 'PERCENT';

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
  /**
   * Mirrored QuikShipX status (Pending / Confirmed / Tracking ID Assigned / In
   * Transit / …), shown as a chip on the Orders list. Null when the order was
   * never published to QuikShipX.
   */
  quikShipXStatus?: string | null;
  /** QuikShipX's own order id (quoted to track on their portal); null until published. */
  quikShipXOrderId?: string | null;
  /** The allotted AWB; null until a tracking id is assigned. */
  quikShipXAwb?: string | null;
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
  /** Per-line GST amount extracted from the GST-inclusive net (Money string). */
  gstAmount?: Money | null;
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
  /** The order-level discount kind, when one was applied (else null). */
  discountType?: OrderDiscountType | null;
  /** The raw discount value as entered (rupee amount for FLAT, percent for PERCENT). */
  discountValue?: Money | null;
  /** GST-inclusive subtotal (Σ line totals) before discount (Money string). */
  subtotalAmount?: Money;
  /** Aggregate GST contained within the total (Money string). */
  gstAmount?: Money;
  paymentScreenshotAvailable: boolean;
  /** Optional free-text order note captured at order entry. */
  notes?: string | null;
  /**
   * Optional buyer GSTIN captured at order entry (gst-filing-compliance Req 1);
   * present only for registered-business (B2B) buyers. Surfaced read-only on the
   * order-detail drawer.
   */
  buyerGstin?: string | null;
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
  /**
   * QuikShipX shipment mirror (courier integration): the mirrored QuikShipX
   * status label (Pending / Confirmed / Tracking ID Assigned / In Transit /
   * Out For Delivery / Delivered / …), the QuikShipX-hosted shipping-label PDF
   * URL, QuikShipX's own order id, and whether it was booked with the TEST
   * secret. All null/false until the order is published to QuikShipX.
   */
  quikShipXStatus?: string | null;
  quikShipXLabelUrl?: string | null;
  quikShipXOrderId?: string | null;
  quikShipXTest?: boolean;
}

/**
 * The QuikShipX shipment mirror for an order, from
 * {@code GET /api/orders/{id}/quikshipx} (mirrors the backend
 * {@code QuikShipXController.ShipmentView}). 404 when not yet published.
 */
export interface QuikShipShipment {
  orderId: number;
  orderCode: string;
  shipperOrderId?: string | null;
  quikShipXStatus?: string | null;
  awb?: string | null;
  courierId?: string | null;
  subCourierName?: string | null;
  labelUrl?: string | null;
  test: boolean;
  lastStatusRaw?: string | null;
  lastSyncedAt?: string | null;
}

/** Acknowledgement of a QuikShipX publish request. */
export interface QuikShipPublishAck {
  queued: boolean;
  message: string;
}

/** One courier scan event in the QuikShipX tracking timeline. */
export interface QuikShipScan {
  status?: string | null;
  location?: string | null;
  instructions?: string | null;
  scanAt?: string | null;
}

/**
 * Live tracking for an order, from {@code GET /api/orders/{id}/quikshipx/track}
 * (mirrors the backend {@code QuikShipXService.TrackView}). `message` is set when
 * the shipment is not yet trackable (empty timeline).
 */
export interface QuikShipTracking {
  quikShipXStatus?: string | null;
  awb?: string | null;
  lastSyncedAt?: string | null;
  scans: QuikShipScan[];
  message?: string | null;
}
