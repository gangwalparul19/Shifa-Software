import { Money, PaymentStatus } from 'core';

/** Payment authenticity verification state (mirrors the backend enum, §4.4). */
export type PaymentVerificationStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';

/**
 * A row in the Payment Verifier's queue, from {@code GET /api/payments/queue}
 * (mirrors the backend {@code PaymentQueueRow}, product-audit §4.4).
 */
export interface PaymentQueueRow {
  id: number;
  orderCode: string;
  customerName: string;
  customerMobile: string;
  totalAmount: Money;
  amountReceived: Money;
  paymentStatus: PaymentStatus;
  paymentScreenshotAvailable: boolean;
  verificationStatus: PaymentVerificationStatus;
  createdAt?: string;
  /** Name of the salesperson who punched the order (created_by → display name). */
  salespersonName?: string | null;
  /**
   * Other orders whose payment proof is byte-identical to this one
   * (duplicate-screenshot detection, V72), each as an {orderId, orderCode} ref.
   * Non-empty = a fraud/mistake flag. The UI links each: click the code to view
   * THAT order's screenshot (by orderId) and open its details (by orderCode).
   */
  duplicateOrders?: DuplicateOrderRef[];
}

/** A reference to another order sharing this order's payment proof (V72). */
export interface DuplicateOrderRef {
  orderId: number;
  orderCode: string;
}
