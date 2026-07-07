/**
 * Lifecycle status of a purchase order. POs are created directly as
 * {@code ORDERED}; they are receivable while {@code ORDERED} or
 * {@code PARTIALLY_RECEIVED} and cancellable while {@code DRAFT} or
 * {@code ORDERED}.
 */
export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'ORDERED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CANCELLED';

/**
 * A row in the paged purchase-orders listing
 * ({@code GET /api/admin/purchase-orders}). Mirrors the backend
 * {@code PurchaseOrderSummaryResponse}.
 */
export interface PurchaseOrderSummaryResponse {
  id: number;
  poNumber: string;
  supplierId: number;
  status: PurchaseOrderStatus;
  totalAmount: number;
  itemCount: number;
  createdAt: string;
  receivedAt?: string | null;
}

/** A single line item on a purchase order. */
export interface PurchaseOrderItemResponse {
  id: number;
  productId: number;
  quantity: number;
  unitCost: number;
  receivedQuantity: number;
  lineTotal: number;
}

/**
 * Full purchase-order detail ({@code GET /api/admin/purchase-orders/{id}}).
 * Mirrors the backend {@code PurchaseOrderResponse} (includes line items).
 */
export interface PurchaseOrderResponse {
  id: number;
  poNumber: string;
  supplierId: number;
  status: PurchaseOrderStatus;
  notes?: string | null;
  totalAmount: number;
  createdBy: number;
  createdAt: string;
  receivedAt?: string | null;
  items: PurchaseOrderItemResponse[];
}

/** A line on a create-PO request. */
export interface CreatePurchaseOrderItem {
  productId: number;
  quantity: number;
  unitCost: number;
}

/** Payload for creating a purchase order ({@code POST /api/admin/purchase-orders}). */
export interface CreatePurchaseOrderRequest {
  supplierId: number;
  notes?: string | null;
  items: CreatePurchaseOrderItem[];
}

/** A single received line on a receive request. */
export interface ReceivePurchaseOrderLine {
  itemId: number;
  receivedQuantity: number;
}

/** Payload for receiving a purchase order ({@code POST .../{id}/receive}). */
export interface ReceivePurchaseOrderRequest {
  lines: ReceivePurchaseOrderLine[];
}
