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
  PENDING_ADMIN_APPROVAL = 'Pending_Admin_Approval',
  APPROVED = 'Approved',
  REJECTED = 'Rejected',
  CANCELLED = 'Cancelled',
  LABEL_GENERATED = 'Label_Generated',
  PACKED = 'Packed',
  HANDED_TO_DELIVERY = 'Handed_To_Delivery',
  COURIER_ASSIGNED = 'Courier_Assigned',
  DISPATCHED = 'Dispatched',
  IN_TRANSIT = 'In_Transit',
  OUT_FOR_DELIVERY = 'Out_For_Delivery',
  DELIVERED = 'Delivered',
  CUSTOMER_REJECTED = 'Customer_Rejected',
  DELIVERY_FAILED = 'Delivery_Failed',
  RTO = 'RTO',
  COURIER_LOST = 'Courier_Lost',
  COD_COLLECTED = 'COD_Collected',
  CLOSED = 'Closed',
}

/** Origin of the order. */
export enum OrderSource {
  STOREFRONT = 'STOREFRONT',
  SALESPERSON = 'SALESPERSON',
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
