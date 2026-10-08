import { Money } from 'core';

/** The lifecycle status of a return / refund request. */
export type ReturnStatus = 'REQUESTED' | 'APPROVED' | 'REFUNDED' | 'REJECTED';

/** How a refund was paid back (ENHANCEMENT 2.3, backend {@code RefundMethod}). */
export type RefundMethod = 'CASH' | 'UPI' | 'BANK_TRANSFER' | 'ORIGINAL_PAYMENT' | 'COD_NOT_COLLECTED';

/** Human labels for the refund methods, for the refund modal + list display. */
export const REFUND_METHOD_LABELS: Record<RefundMethod, string> = {
  CASH: 'Cash',
  UPI: 'UPI',
  BANK_TRANSFER: 'Bank transfer',
  ORIGINAL_PAYMENT: 'Back to original payment',
  COD_NOT_COLLECTED: 'Nothing to refund (COD)',
};

/** Ordered {value,label} options for the refund-method picker. */
export const REFUND_METHOD_OPTIONS: { value: RefundMethod; label: string }[] = (
  ['CASH', 'UPI', 'BANK_TRANSFER', 'ORIGINAL_PAYMENT', 'COD_NOT_COLLECTED'] as RefundMethod[]
).map((value) => ({ value, label: REFUND_METHOD_LABELS[value] }));

/**
 * A return / refund request returned by the admin returns endpoints
 * ({@code /api/admin/returns}). Mirrors the backend {@code ReturnResponse}.
 */
export interface ReturnResponse {
  id: number;
  orderId: number;
  /** The order's human-readable code (e.g. SHR-20260916-JGM9); null only if the order no longer exists. */
  orderCode?: string | null;
  reason: string;
  notes?: string | null;
  status: ReturnStatus;
  /** Cash refunded to the customer (money out) — zero for a pure COD RTO. */
  refundAmount?: Money | null;
  /**
   * GST-inclusive value of supply reversed by the credit note — the full invoice
   * value for a whole-consignment return/RTO, regardless of cash collected. This
   * is what GSTR-1 CDNR/CDNUR reports. Null on returns created before the two
   * amounts were separated.
   */
  creditNoteValue?: Money | null;
  restocked: boolean;
  createdBy?: string | null;
  /** Name of the salesperson who punched the ORDER (created_by → display name); null when unknown. */
  salespersonName?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  /** How the refund was paid back (ENHANCEMENT 2.3); null when not refunded / unspecified. */
  refundMethod?: RefundMethod | null;
}

/**
 * Payload for creating a return ({@code POST /api/admin/returns}). {@code orderId}
 * accepts either the order's numeric id or its human-readable order code
 * (e.g. {@code SHR-20260916-JGM9}) — the backend resolves whichever is supplied.
 */
export interface CreateReturnRequest {
  orderId: string;
  reason: string;
  notes?: string;
}

/** Payload for approving a return (optionally restocking + setting a refund). */
export interface ApproveReturnRequest {
  restock: boolean;
  refundAmount?: number;
}

/** Payload for rejecting a return. */
export interface RejectReturnRequest {
  notes?: string;
}

/** Payload for marking a return refunded. */
export interface RefundReturnRequest {
  refundAmount: number;
  /** How the refund was paid back (ENHANCEMENT 2.3); optional. */
  refundMethod?: RefundMethod | null;
}
