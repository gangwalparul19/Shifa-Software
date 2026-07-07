import { Money, OrderSource, PaymentStatus } from 'core';

/** A line within an approval-queue item (mirrors the backend review projection). */
export interface ApprovalLineItem {
  productId?: number;
  productName: string;
  quantity: number;
  rate: Money;
  lineTotal: Money;
}

/**
 * A pending-approval order with the review details the admin needs on screen.
 * Mirrors the backend {@code ApprovalQueueItemResponse}
 * ({@code GET /api/admin/orders/approval-queue}, Req 9.1, 9.2).
 */
export interface ApprovalQueueItem {
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
  codAmount: Money;
  paymentStatus: PaymentStatus;
  paymentScreenshotAvailable: boolean;
  items: ApprovalLineItem[];
  createdAt?: string;
}
