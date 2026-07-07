import { Money, OrderStatus, PaymentStatus } from 'core';

/**
 * Compact order summary returned inside a successful scan response. Mirrors the
 * backend {@code OrderSummaryResponse}.
 */
export interface ScannedOrderSummary {
  id: number;
  orderCode: string;
  customerName: string;
  customerMobile: string;
  orderStatus: OrderStatus | string;
  paymentStatus: PaymentStatus | string;
  totalAmount: Money;
  codAmount: Money;
  createdAt?: string;
}

/**
 * Success body of {@code POST /api/packing/scan} (Req 11.1). Mirrors the backend
 * {@code PackingScanResponse}: a confirmation message plus the packed order.
 */
export interface PackingScanResponse {
  message: string;
  order: ScannedOrderSummary;
}

/** How a single scan resolved, for the in-session scan log. */
export type ScanOutcome = 'packed' | 'not-recognized' | 'wrong-status' | 'error';

/** One entry in the running scan log shown in the UI. */
export interface ScanLogEntry {
  barcode: string;
  outcome: ScanOutcome;
  message: string;
  /** Present for the wrong-status outcome (Req 11.4). */
  currentStatus?: string;
  at: Date;
}
