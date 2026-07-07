import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Category, Product } from 'core';

/** Filter/sort options for the catalog listing (Catalog & Discovery). */
export interface CatalogFilters {
  q?: string;
  category?: string;
  minPrice?: string | number;
  maxPrice?: string | number;
  sort?: 'relevance' | 'price_asc' | 'price_desc' | 'name_asc' | 'newest';
  inStock?: boolean;
  featured?: boolean;
}

/**
 * Storefront catalog data access (Req 1.1, 1.2, 1.3 + Catalog & Discovery).
 *
 * Thin wrapper over the shared {@link ApiClient} that calls the public catalog
 * endpoints. All returned products are already filtered to published on the
 * server, so the UI never has to re-check visibility.
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly api = inject(ApiClient);

  /** Published catalog, or search results when a non-blank query is supplied. */
  list(query?: string): Observable<Product[]> {
    const trimmed = query?.trim();
    const options = trimmed ? { params: { q: trimmed } } : undefined;
    return this.api.get<Product[]>('/api/catalog/products', options);
  }

  /** Filtered + sorted catalog listing wired to the query params. */
  search(filters: CatalogFilters): Observable<Product[]> {
    const params: Record<string, string | number | boolean> = {};
    const q = filters.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (filters.category) {
      params['category'] = filters.category;
    }
    if (filters.minPrice !== undefined && filters.minPrice !== '') {
      params['minPrice'] = filters.minPrice;
    }
    if (filters.maxPrice !== undefined && filters.maxPrice !== '') {
      params['maxPrice'] = filters.maxPrice;
    }
    if (filters.sort && filters.sort !== 'relevance') {
      params['sort'] = filters.sort;
    }
    if (filters.inStock) {
      params['inStock'] = true;
    }
    if (filters.featured) {
      params['featured'] = true;
    }
    return this.api.get<Product[]>('/api/catalog/products', { params });
  }

  /** Product detail; the server returns 404 for hidden/unavailable products. */
  detail(id: number): Observable<Product> {
    return this.api.get<Product>(`/api/catalog/products/${id}`);
  }

  /** "You may also like" suggestions for a product. */
  related(id: number): Observable<Product[]> {
    return this.api.get<Product[]>(`/api/catalog/products/${id}/related`);
  }

  /** Active categories for navigation and filters. */
  categories(): Observable<Category[]> {
    return this.api.get<Category[]>('/api/catalog/categories');
  }
}
