import { Money, OrderSource, OrderStatus, PaymentStatus, RejectReason } from 'core';

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
  /**
   * Additional payment-proof storage keys beyond {@link paymentScreenshotKey}
   * (V65), for orders with more than one proof — a part payment plus the balance,
   * a UPI receipt plus a bank confirmation, and so on. Omitted for a single proof.
   */
  paymentScreenshotKeys?: string[];
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
  /**
   * Optional per-order delivery method: "QUIKSHIPX" (default when omitted) or
   * "IN_HOUSE" to skip the QuikShipX courier integration entirely and have
   * Shifa's own team deliver the order (mirrors the backend).
   */
  deliveryMethod?: DeliveryMethod;
}

/** The kind of order-level discount (mirrors the backend). */
export type OrderDiscountType = 'FLAT' | 'PERCENT';

/**
 * Admin edit-order payload posted to {@code PUT /api/admin/orders/{id}}
 * (mirrors the backend {@code UpdateOrderRequest}). Lets an ADMIN correct the
 * customer / shipping / line-item / lead-source / note / GSTIN / discount
 * details a salesperson entered — payment fields (amount received / screenshot)
 * are deliberately excluded; only allowed while the order is still
 * {@code Pending_Admin_Approval} or {@code Approved}.
 */
export interface UpdateOrderRequest {
  customerName: string;
  customerMobile: string;
  alternateMobile?: string;
  customerEmail?: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  items: CreateOrderLineItem[];
  leadSource: LeadSource;
  leadSourceNote?: string;
  notes?: string;
  buyerGstin?: string;
  discountType?: OrderDiscountType;
  discountValue?: number;
}

/** Per-order delivery method (mirrors the backend {@code DeliveryMethod} enum). */
export type DeliveryMethod = 'QUIKSHIPX' | 'IN_HOUSE';

/**
 * The delivery stages a human may set on an IN_HOUSE order via
 * {@code POST /api/orders/{id}/delivery-status} (mirrors the backend's
 * MANUAL_DELIVERY_STAGES whitelist). An in-house order has no courier partner, so
 * no webhook ever reports progress — staff advance it by hand.
 */
export type ManualDeliveryStage =
  | 'DISPATCHED'
  | 'IN_TRANSIT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CUSTOMER_REJECTED'
  | 'DELIVERY_FAILED';

/**
 * The internal warehouse steps, settable manually on ANY order (they mirror the
 * Packing page's own actions) — as opposed to the post-handover stages below,
 * which only apply to in-house orders.
 */
export type ManualPackingStage = 'PACKED' | 'HANDED_TO_DELIVERY';

/** Every status the manual status control can set (mirrors the backend whitelist). */
export type ManualStage = ManualPackingStage | ManualDeliveryStage;

/** Selectable manual stages, in real-world lifecycle order. */
export const MANUAL_DELIVERY_STAGE_OPTIONS: {
  value: ManualStage;
  label: string;
  hint: string;
}[] = [
  { value: 'PACKED', label: 'Packed', hint: 'Items picked and boxed, ready to hand over.' },
  { value: 'HANDED_TO_DELIVERY', label: 'Handed to delivery', hint: 'Given to whoever is carrying it (own team, bus, courier counter).' },
  { value: 'DISPATCHED', label: 'Dispatched', hint: 'Parcel has left with the carrier / vehicle.' },
  { value: 'IN_TRANSIT', label: 'In transit', hint: 'On the way to the destination city.' },
  { value: 'OUT_FOR_DELIVERY', label: 'Out for delivery', hint: 'Being delivered to the customer today.' },
  { value: 'DELIVERED', label: 'Delivered', hint: 'Handed to the customer — also settles the order (payment collected / closed).' },
  { value: 'CUSTOMER_REJECTED', label: 'Customer refused', hint: 'Customer declined the parcel at the door.' },
  { value: 'DELIVERY_FAILED', label: 'Delivery failed', hint: 'Attempted but could not be delivered.' },
];

/**
 * The stages that may legally follow a given status, mirroring the backend
 * {@code OrderStatus} transition table (only the manually-settable subset — RTO
 * has its own scan flow with a required reason, and settlement is automatic).
 * Anything not listed here has no manual next step.
 */
export const MANUAL_NEXT_STAGES: Record<string, ManualStage[]> = {
  LABEL_GENERATED: ['PACKED'],
  PACKED: ['HANDED_TO_DELIVERY'],
  HANDED_TO_DELIVERY: ['DISPATCHED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'],
  DISPATCHED: ['IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'],
  IN_TRANSIT: ['OUT_FOR_DELIVERY', 'DELIVERED'],
  OUT_FOR_DELIVERY: ['DELIVERED', 'CUSTOMER_REJECTED', 'DELIVERY_FAILED'],
  // A failed/refused attempt still has the parcel in hand: re-attempt it. Giving up
  // instead means marking it RTO (Packing → Mark RTO), which raises the credit note.
  DELIVERY_FAILED: ['OUT_FOR_DELIVERY'],
  CUSTOMER_REJECTED: ['OUT_FOR_DELIVERY'],
};

/** Statuses where a delivery attempt has failed and can be retried or closed out. */
export const FAILED_DELIVERY_STATUSES = ['DELIVERY_FAILED', 'CUSTOMER_REJECTED'];

/** The warehouse steps that apply to any order regardless of delivery method. */
export const MANUAL_PACKING_STAGES: ManualStage[] = ['PACKED', 'HANDED_TO_DELIVERY'];

/**
 * A selectable delivery partner for the "Assign courier" dropdown (delivery-
 * partner dropdown enhancement), from
 * {@code GET /api/admin/orders/courier-companies}. Mirrors the backend
 * {@code CourierCompanyResponse}.
 */
export interface CourierCompanyOption {
  id: number;
  name: string;
}

/**
 * Selectable delivery-method options — shown to the ADMIN at approval time
 * (the salesperson no longer chooses this at order entry; every order defaults
 * to IN_HOUSE and the admin picks/overrides the delivery partner on approve).
 */
export const DELIVERY_METHOD_OPTIONS: { value: DeliveryMethod; label: string }[] = [
  { value: 'QUIKSHIPX', label: 'QuikShipX (courier partner)' },
  { value: 'IN_HOUSE', label: 'In-house delivery (own team)' },
];

/**
 * Result of {@code POST /api/orders/payment-screenshots} (mirrors the backend
 * {@code ScreenshotUploadResponse}) — the storage {@code key} to attach to a
 * subsequent order.
 */
/**
 * One payment proof attached to an order (V65), from
 * {@code GET /api/orders/{id}/payment-screenshots}. Metadata only — the bytes are
 * fetched per proof as an authenticated Blob.
 */
export interface PaymentScreenshot {
  id: number;
  filename?: string | null;
  contentType?: string | null;
  byteSize?: number | null;
  /** Whether this is the order's first proof (the one the legacy endpoint serves). */
  primary: boolean;
  createdAt?: string;
}

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
  /**
   * Whether an ACTIVE (not rejected/cancelled) order already exists for this
   * mobile TODAY — a same-day duplicate. When true the New Order form warns the
   * salesperson (and the server hard-blocks creating a second one).
   */
  hasTodayOrder: boolean;
  /** The existing today order's code (null when none). */
  todayOrderCode: string | null;
  /** Display name of who placed today's order (null when none/unknown). */
  todaySalespersonName: string | null;
  /** Whether today's order was placed by the current user (vs another salesperson). */
  todayCreatedByMe: boolean;
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
  /** Name of the salesperson who punched the order (created_by → display name). */
  salespersonName?: string | null;
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
  /**
   * Per-order delivery method — defaults to IN_HOUSE at order entry; the admin
   * picks/overrides the delivery partner when approving. IN_HOUSE orders never
   * get a QuikShipX shipment — the QuikShipX card is hidden and an in-house
   * delivery card with a "Mark delivered" action is shown instead.
   */
  deliveryMethod?: DeliveryMethod;
  orderStatus: OrderStatus;
  paymentStatus: PaymentStatus;
  customerName: string;
  customerMobile: string;
  /** Lead source and optional note returned on order detail for safe reorder defaults. */
  customerEmail?: string | null;
  leadSource?: LeadSource | null;
  leadSourceNote?: string | null;
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
  /** Their contact number, when captured — so staff can call whoever carries the parcel. */
  handoverPhone?: string | null;
  /**
   * Optional vehicle / transport reference for an in-house delivery (bus vehicle
   * no., train no., taxi registration, own van). An in-house order has no AWB, so
   * this plus {@link handoverName} identifies the shipment in the real world.
   */
  vehicleNumber?: string | null;
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
  /**
   * The admin's reason for rejecting the order (Req 9.4); present only when
   * {@code orderStatus === 'REJECTED'}, so the salesperson can see why and
   * rework the order.
   */
  rejectionReason?: string | null;
  /**
   * The categorized reason + optional note captured when a packer/admin
   * manually marked this order RTO via the Mark RTO scan flow. Both null
   * unless the order was ever marked RTO that way (mirrors backend RtoReason).
   */
  rtoReason?: RtoReasonValue | null;
  rtoReasonNote?: string | null;
  /**
   * The order's full status-history timeline (enhancement: order status
   * timeline), oldest first, mirroring the backend {@code StatusHistoryEntryResponse}.
   */
  statusHistory?: OrderStatusHistoryEntry[];
  /** Name of the salesperson who punched the order (created_by → display name). */
  salespersonName?: string | null;
  /**
   * The categorized rejection reason (rejection-status feature): RATE_ISSUE /
   * ADDRESS_PINCODE_ISSUE (admin REJECTED) or PAYMENT_ISSUE (PAYMENT_REJECTED
   * from the payment panel). Null unless the order was rejected. The
   * accompanying free-text is {@code rejectionReason}.
   */
  rejectReason?: RejectReason | null;
  /**
   * The payment verifier's free-text note when the payment was rejected, so the
   * salesperson sees why the payment failed. Null when there's no note.
   */
  paymentVerificationNote?: string | null;
}

/** One status-history row, mirroring the backend {@code StatusHistoryEntryResponse}. */
export interface OrderStatusHistoryEntry {
  fromStatus: OrderStatus | string | null;
  toStatus: OrderStatus | string;
  actor: string;
  source: string;
  changedAt: string;
}

/** Mirrors the backend RtoReason enum (kept in sync with packing.model.ts). */
export type RtoReasonValue =
  | 'CUSTOMER_UNAVAILABLE'
  | 'CUSTOMER_REFUSED'
  | 'ADDRESS_ISSUE'
  | 'DAMAGED_IN_TRANSIT'
  | 'OTHER';

/** Human-readable label for an RtoReasonValue, matching packing's RTO_REASON_OPTIONS. */
export const RTO_REASON_LABELS: Record<RtoReasonValue, string> = {
  CUSTOMER_UNAVAILABLE: 'Customer unavailable',
  CUSTOMER_REFUSED: 'Customer refused delivery',
  ADDRESS_ISSUE: 'Address issue',
  DAMAGED_IN_TRANSIT: 'Damaged in transit',
  OTHER: 'Other',
};

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
