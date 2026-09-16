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
  reason: string;
  notes?: string | null;
  status: ReturnStatus;
  refundAmount?: Money | null;
  restocked: boolean;
  createdBy?: string | null;
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
