import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Money, PageResponse, Product, ProductVisibility, StockStatus } from 'core';

/**
 * Read-only per-product sales stats for the product-detail "Sales Overview"
 * card ({@code GET /api/admin/products/{id}/stats}). Mirrors the backend
 * {@code ProductSalesStatsResponse}: both figures are scoped to the current
 * calendar month and exclude non-revenue (rejected/cancelled) orders.
 */
export interface ProductSalesStats {
  /** Revenue for the product this month (Money decimal string; "0.00" when none). */
  salesThisMonth: Money;
  /** Distinct qualifying orders containing the product this month (0 when none). */
  ordersThisMonth: number;
}

/** Filters + paging for the admin product grid (server-side, Wave 2). */
export interface ProductPageQuery {
  q?: string | null;
  /** Category id or slug. */
  category?: string | number | null;
  visibility?: ProductVisibility | string | null;
  stockStatus?: StockStatus | string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "name,asc"). */
  sort?: string | null;
}

/**
 * Payload for creating/updating a product. Mirrors the backend
 * {@code ProductRequest} ({@code POST}/{@code PUT} {@code /api/admin/products}).
 */
export interface ProductRequest {
  sku: string;
  name: string;
  description?: string;
  mrp: string;
  salePrice: string;
  /** Optional minimum selling price (per-line floor); must be ≤ salePrice ≤ mrp. */
  minimumRate?: string | null;
  hsnCode?: string;
  /**
   * Optional per-product GST rate percent (e.g. "12" or "18.00"); null/omitted
   * falls back to the settings default rate.
   */
  gstRate?: string | null;
  /** Optional pack size / weight / volume descriptor, e.g. "100ML". */
  wtMl?: string | null;
  visibility: ProductVisibility;
  /** Optional category id; null/undefined leaves the product uncategorised. */
  categoryId?: number | null;
  /** On-hand units; only meaningful when {@link trackInventory} is true. */
  stockQuantity?: number;
  /** Whether the product tracks inventory (else it always reads in-stock). */
  trackInventory?: boolean;
  /** Whether the product joins the storefront featured collection. */
  featured?: boolean;
}

/** The per-row action decided by the CSV import (dry-run or real). */
export type ImportRowAction = 'CREATE' | 'UPDATE' | 'SKIP' | 'ERROR';

/** A single row's outcome in a CSV import (dry-run preview or real run). */
export interface ImportRowResult {
  rowNumber: number;
  sku?: string | null;
  action: ImportRowAction;
  message?: string | null;
}

/**
 * Result of a bulk product CSV import ({@code POST /api/admin/products/import}).
 * When {@code dryRun} is true the counts + rows describe what *would* happen.
 */
export interface ImportResult {
  dryRun: boolean;
  totalRows: number;
  created: number;
  updated: number;
  skipped: number;
  errors: number;
  rows: ImportRowResult[];
}

/**
 * Data access for admin product management (Req 6.1-6.4).
 *
 * <p>Reads the full product set (published AND hidden) from the admin listing
 * endpoint — distinct from the public catalog, which only exposes published
 * products. Create/update calls go through the shared {@link ApiClient} so the
 * auth interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class ProductsService {
  private readonly api = inject(ApiClient);

  /** All products for the management grid, published and hidden (Req 6.3, 6.4). */
  list(): Observable<Product[]> {
    return this.api.get<Product[]>('/api/admin/products');
  }

  /**
   * Server-side paginated, filtered, sorted products (Wave 2). Backed by
   * {@code GET /api/admin/products/page}. Empty/nullish filters are omitted.
   */
  page(query: ProductPageQuery): Observable<PageResponse<Product>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    const q = typeof query.q === 'string' ? query.q.trim() : '';
    if (q) {
      params['q'] = q;
    }
    if (query.category != null && query.category !== '') {
      params['category'] = query.category;
    }
    if (query.visibility) {
      params['visibility'] = query.visibility;
    }
    if (query.stockStatus) {
      params['stockStatus'] = query.stockStatus;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<Product>>('/api/admin/products/page', { params });
  }

  /** A single product for editing, regardless of visibility (Req 6.3). */
  get(id: number): Observable<Product> {
    return this.api.get<Product>(`/api/admin/products/${id}`);
  }

  /**
   * Per-product current-month sales stats for the detail "Sales Overview"
   * (Req 9.2). Backed by {@code GET /api/admin/products/{id}/stats}; read access
   * matches the other product read endpoints (ADMIN or SALESPERSON).
   */
  stats(id: number): Observable<ProductSalesStats> {
    return this.api.get<ProductSalesStats>(`/api/admin/products/${id}/stats`);
  }

  /** Create a product; a duplicate SKU is rejected with a 409 (Req 6.1, 6.2). */
  create(request: ProductRequest): Observable<Product> {
    return this.api.post<Product>('/api/admin/products', request);
  }

  /** Update a product, including its visibility (Req 6.3, 6.4). */
  update(id: number, request: ProductRequest): Observable<Product> {
    return this.api.put<Product>(`/api/admin/products/${id}`, request);
  }

  /**
   * Bulk-import products from a CSV file (Set B — Feature 5). Posts the file as
   * the multipart part {@code file} to {@code POST /api/admin/products/import}.
   * Pass {@code dryRun=true} first to preview the outcome, then {@code false} to
   * commit. Angular sets the multipart {@code Content-Type} itself for a
   * {@link FormData} body.
   */
  importCsv(file: File, dryRun: boolean): Observable<ImportResult> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<ImportResult>('/api/admin/products/import', form, {
      params: { dryRun },
    });
  }
}
