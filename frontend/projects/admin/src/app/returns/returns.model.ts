import { Money } from 'core';

/** The lifecycle status of a return / refund request. */
export type ReturnStatus = 'REQUESTED' | 'APPROVED' | 'REFUNDED' | 'REJECTED';

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
}
