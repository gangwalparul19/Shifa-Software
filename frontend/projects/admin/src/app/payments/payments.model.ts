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
}
