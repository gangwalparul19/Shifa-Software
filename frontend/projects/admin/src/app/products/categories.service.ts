import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Category } from 'core';

/**
 * Payload for creating/updating a category. Mirrors the backend
 * {@code CategoryRequest} ({@code POST}/{@code PUT} {@code /api/admin/categories}).
 * A blank slug is derived from the name by the server.
 */
export interface CategoryRequest {
  name: string;
  slug?: string;
  description?: string;
  sortOrder?: number;
  active?: boolean;
}

/**
 * Data access for admin category management (Catalog & Discovery).
 *
 * <p>Reads ALL categories (active + inactive) from the admin listing endpoint,
 * distinct from the public list which only exposes active ones. Mutations go
 * through the shared {@link ApiClient} so the auth interceptor attaches the
 * bearer token.
 */
@Injectable({ providedIn: 'root' })
export class CategoriesService {
  private readonly api = inject(ApiClient);

  /** All categories for the management grid, active and inactive. */
  list(): Observable<Category[]> {
    return this.api.get<Category[]>('/api/admin/categories');
  }

  create(request: CategoryRequest): Observable<Category> {
    return this.api.post<Category>('/api/admin/categories', request);
  }

  update(id: number, request: CategoryRequest): Observable<Category> {
    return this.api.put<Category>(`/api/admin/categories/${id}`, request);
  }

  /** Soft-deactivate a category (it disappears from the public list). */
  deactivate(id: number): Observable<Category> {
    return this.api.delete<Category>(`/api/admin/categories/${id}`);
  }
}
