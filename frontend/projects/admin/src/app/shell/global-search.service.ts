import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Money, OrderStatus } from 'core';

/** A matched order in the global-search results. */
export interface SearchOrderHit {
  id: number;
  orderCode: string;
  customerName: string;
  orderStatus: OrderStatus | string;
  totalAmount: Money;
}

/** A matched product in the global-search results. */
export interface SearchProductHit {
  id: number;
  sku: string;
  name: string;
}

/** A matched customer in the global-search results. */
export interface SearchCustomerHit {
  id: number;
  name: string;
  mobile: string;
}

/** Grouped results returned by {@code GET /api/admin/search?q=}. */
export interface SearchResults {
  orders: SearchOrderHit[];
  products: SearchProductHit[];
  customers: SearchCustomerHit[];
}

/**
 * Data access for the admin global search (Wave 2). Calls
 * {@code GET /api/admin/search?q=} through the shared {@link ApiClient} so the
 * auth interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class GlobalSearchService {
  private readonly api = inject(ApiClient);

  search(q: string): Observable<SearchResults> {
    return this.api.get<SearchResults>('/api/admin/search', { params: { q } });
  }
}
