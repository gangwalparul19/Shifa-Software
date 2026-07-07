import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Product } from 'core';

/**
 * Read access to published products ({@code GET /api/orders/products}) for the
 * order-entry product picker.
 *
 * <p>Uses the order-entry endpoint (scoped to SALESPERSON/ADMIN) rather than the
 * ADMIN-only {@code /api/admin/products} so a SALESPERSON can populate the picker
 * too. It only ever returns published products, which is exactly what an
 * order-entry selector should offer. (This replaced the retired public catalog
 * endpoint after the storefront was removed.) The shared {@link ApiClient}
 * attaches the base URL; the auth interceptor adds the bearer token when present.
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly api = inject(ApiClient);

  /** Published products, optionally filtered by a name/SKU query {@code q}. */
  products(query?: string | null): Observable<Product[]> {
    const q = query?.trim();
    return this.api.get<Product[]>(
      '/api/orders/products',
      q ? { params: { q } } : undefined,
    );
  }
}
