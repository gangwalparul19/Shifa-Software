import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, StockStatus } from 'core';

/**
 * Stock-focused product projection returned by the admin inventory endpoints.
 * Mirrors the backend {@code InventoryProductResponse} ({@code GET
 * /api/admin/inventory} and {@code /low-stock}, and the response of restock /
 * adjust).
 */
export interface InventoryProduct {
  id: number;
  sku: string;
  name: string;
  trackInventory: boolean;
  stockQuantity: number;
  /** The effective (resolved) low-stock threshold. */
  lowStockThreshold: number;
  /** Derived status using the effective threshold. */
  stockStatus: StockStatus;
}

/**
 * The kind of stock movement recorded in the ledger. Mirrors the backend
 * {@code StockMovementType} enum.
 */
export enum StockMovementType {
  RESTOCK = 'RESTOCK',
  ADJUSTMENT = 'ADJUSTMENT',
  SALE = 'SALE',
  RETURN = 'RETURN',
}

/**
 * A single stock-movement ledger row. Mirrors the backend
 * {@code StockMovementResponse} ({@code GET
 * /api/admin/inventory/{productId}/movements}).
 */
export interface StockMovement {
  id: number;
  productId: number;
  /** Signed quantity change (positive adds, negative removes). */
  delta: number;
  type: StockMovementType;
  reason?: string | null;
  /** On-hand quantity immediately after the movement. */
  balanceAfter: number;
  /** Acting user id, or null for system movements. */
  createdBy?: number | null;
  /** ISO date-time the movement was recorded. */
  createdAt: string;
}

/**
 * Request body for a restock: add a positive quantity of stock with an optional
 * reason. Mirrors the backend {@code RestockRequest}.
 */
export interface RestockRequest {
  quantity: number;
  reason?: string | null;
}

/**
 * Request body for a signed stock adjustment (positive to add, negative to
 * remove) with an optional reason; a zero delta is rejected server-side.
 * Mirrors the backend {@code AdjustRequest}.
 */
export interface AdjustRequest {
  delta: number;
  reason?: string | null;
}

/**
 * Data access for admin inventory / stock management (Wave 3, Feature 1).
 *
 * <p>Backed by {@code /api/admin/inventory} (ADMIN-only on the backend). Calls
 * go through the shared {@link ApiClient} so the auth interceptor attaches the
 * bearer token. The list endpoints are un-paginated (plain arrays), so the
 * component filters/sorts client-side.
 */
@Injectable({ providedIn: 'root' })
export class InventoryService {
  private readonly api = inject(ApiClient);

  /** All products with their current stock info. */
  list(): Observable<InventoryProduct[]> {
    return this.api.get<InventoryProduct[]>('/api/admin/inventory');
  }

  /**
   * Tracked products that are low on stock. When {@code includeOutOfStock} is
   * true, out-of-stock tracked products are included too.
   */
  lowStock(includeOutOfStock = false): Observable<InventoryProduct[]> {
    return this.api.get<InventoryProduct[]>('/api/admin/inventory/low-stock', {
      params: { includeOutOfStock },
    });
  }

  /** Recent stock movements for a product, newest first. */
  movements(productId: number): Observable<StockMovement[]> {
    return this.api.get<StockMovement[]>(`/api/admin/inventory/${productId}/movements`);
  }

  /** Adds stock to a product (RESTOCK); returns the updated stock info. */
  restock(productId: number, request: RestockRequest): Observable<InventoryProduct> {
    return this.api.post<InventoryProduct>(`/api/admin/inventory/${productId}/restock`, request);
  }

  /** Applies a signed stock adjustment; returns the updated stock info. */
  adjust(productId: number, request: AdjustRequest): Observable<InventoryProduct> {
    return this.api.post<InventoryProduct>(`/api/admin/inventory/${productId}/adjust`, request);
  }
}
