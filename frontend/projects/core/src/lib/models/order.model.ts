import { LineItem } from './line-item.model';
import { Money } from './money.model';

/**
 * Payment classification derived from Amount_Received vs Total_Amount.
 * (Backend Req 7.7-7.9.)
 */
export enum PaymentStatus {
  /** Amount_Received === Total_Amount. */
  FULLY_PAID = 'FULLY_PAID',
  /** 0 < Amount_Received < Total_Amount. */
  PARTIALLY_PAID = 'PARTIALLY_PAID',
  /** Amount_Received === 0; full amount collected on delivery. */
  COD = 'COD',
}

/**
 * The order lifecycle states defined by the state machine (backend Req 8.1).
 * New orders start in {@link OrderStatus.PENDING_ADMIN_APPROVAL} (Req 8.2).
 *
 * <p>The role-based-order-workflow feature adds three states:
 * {@link OrderStatus.HANDED_TO_DELIVERY} (a handover step between
 * {@link OrderStatus.PACKED} and {@link OrderStatus.COURIER_ASSIGNED}) and two
 * distinct downstream delivery outcomes, {@link OrderStatus.CUSTOMER_REJECTED}
 * and {@link OrderStatus.DELIVERY_FAILED}.
 */
export enum OrderStatus {
  // String values MUST equal the backend enum name() — that's what Jackson puts
  // on the wire (no custom enum serialization). Using the uppercase names (like
  // PaymentStatus) is what makes status comparisons — pill/badge colours, return
  // eligibility, grouping — actually match the API responses.
  PENDING_ADMIN_APPROVAL = 'PENDING_ADMIN_APPROVAL',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED',
  CANCELLED = 'CANCELLED',
  LABEL_GENERATED = 'LABEL_GENERATED',
  PACKED = 'PACKED',
  HANDED_TO_DELIVERY = 'HANDED_TO_DELIVERY',
  COURIER_ASSIGNED = 'COURIER_ASSIGNED',
  DISPATCHED = 'DISPATCHED',
  IN_TRANSIT = 'IN_TRANSIT',
  OUT_FOR_DELIVERY = 'OUT_FOR_DELIVERY',
  DELIVERED = 'DELIVERED',
  CUSTOMER_REJECTED = 'CUSTOMER_REJECTED',
  DELIVERY_FAILED = 'DELIVERY_FAILED',
  RTO = 'RTO',
  REDISPATCH = 'REDISPATCH',
  COD_COLLECTED = 'COD_COLLECTED',
  CLOSED = 'CLOSED',
}

/**
 * Origin channel of the order (mirrors the backend {@code OrderSource}).
 *
 * <p>{@code SHOPIFY_API} orders are ingested from the Shopify storefront and are read-only
 * in the admin app; {@code SHIFA_ADMIN} orders are punched in this portal. {@code STOREFRONT}
 * and {@code SALESPERSON} are legacy values kept for historic rows — the backend folds them
 * onto {@code SHIFA_ADMIN}.
 */
export enum OrderSource {
  STOREFRONT = 'STOREFRONT',
  SALESPERSON = 'SALESPERSON',
  SHOPIFY_API = 'SHOPIFY_API',
  SHIFA_ADMIN = 'SHIFA_ADMIN',
}

/** Mirrors the backend Order DTO (`orders` table + line items). */
export interface Order {
  id: number;
  orderCode: string;
  source: OrderSource;
  createdBy?: number;
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
  paymentStatus: PaymentStatus;
  orderStatus: OrderStatus;
  customerOutstanding?: Money;
  rejectionReason?: string;
  paymentScreenshotKey?: string;
  lineItems: LineItem[];
  createdAt?: string;
  updatedAt?: string;
}
