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
  /**
   * The order's current delivery method (defaults to IN_HOUSE at order entry).
   * Shown/editable in the review drawer so the admin can pick/override the
   * delivery partner as part of approving (in-house-delivery feature).
   */
  deliveryMethod?: 'QUIKSHIPX' | 'IN_HOUSE';
}

/**
 * Result of a bulk-approve call, mirroring the backend {@code BulkActionResult}.
 * {@code succeeded} holds the ids that transitioned; {@code skipped} holds the
 * ids that could not (with a human-readable reason, e.g. no longer pending).
 */
export interface BulkApproveResult {
  succeeded: number[];
  skipped: { id: number; reason: string }[];
}
