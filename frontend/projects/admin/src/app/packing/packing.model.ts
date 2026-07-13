import { Money, OrderStatus, PaymentStatus } from 'core';

/**
 * A single row in a packing work queue, mirroring the backend
 * {@code PackingQueueRow}. Carries the order date and the salesperson name
 * (resolved from {@code created_by}) so the packer/admin sees who punched the
 * order and when.
 */
export interface PackingQueueRow {
  id: number;
  orderCode: string;
  customerName: string;
  /** The salesperson who created the order (null when unknown). */
  salespersonName?: string | null;
  totalAmount: Money;
  /** Order creation timestamp (ISO). */
  createdAt?: string;
  orderStatus: OrderStatus | string;
  paymentStatus: PaymentStatus | string;
}

/**
 * The packing team's work queues (Req 9-11), mirroring the backend
 * {@code PackingQueueResponse}. Each list is oldest-first (FIFO).
 */
export interface PackingQueue {
  /** Orders in {@code Label_Generated} — label printed, ready to be packed. */
  awaitingPacking: PackingQueueRow[];
  /** Orders in {@code Packed} — ready to hand over to the courier. */
  awaitingHandover: PackingQueueRow[];
  /** Orders in {@code Handed_To_Delivery} — ready to dispatch. */
  awaitingDispatch: PackingQueueRow[];
}

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
