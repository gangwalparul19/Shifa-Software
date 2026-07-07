import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Product } from 'core';

/**
 * Read access to the PUBLIC catalog ({@code GET /api/catalog/products}) for the
 * order-entry product picker.
 *
 * <p>The public catalog endpoint is deliberately used (rather than the
 * ADMIN-only {@code /api/admin/products}) so a SALESPERSON can populate the
 * picker too. It only ever returns published products, which is exactly what an
 * order-entry selector should offer. The shared {@link ApiClient} attaches the
 * base URL; the auth interceptor adds the bearer token when present.
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly api = inject(ApiClient);

  /** Published products, optionally filtered by a name/SKU query {@code q}. */
  products(query?: string | null): Observable<Product[]> {
    const q = query?.trim();
    return this.api.get<Product[]>(
      '/api/catalog/products',
      q ? { params: { q } } : undefined,
    );
  }
}
