import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { SupplierRequest, SupplierResponse } from './suppliers.model';

/**
 * Data access for admin Suppliers ({@code /api/admin/suppliers}, ADMIN only).
 * The endpoint returns a plain array (not paged); calls go through the shared
 * {@link ApiClient} (bearer token via interceptor, role enforced by backend).
 */
@Injectable({ providedIn: 'root' })
export class SuppliersService {
  private readonly api = inject(ApiClient);

  /** All suppliers, optionally restricted to active ones. */
  list(activeOnly = false): Observable<SupplierResponse[]> {
    return this.api.get<SupplierResponse[]>('/api/admin/suppliers', {
      params: { activeOnly },
    });
  }

  /** A single supplier by id. */
  get(id: number): Observable<SupplierResponse> {
    return this.api.get<SupplierResponse>(`/api/admin/suppliers/${id}`);
  }

  /** Create a new supplier. */
  create(request: SupplierRequest): Observable<SupplierResponse> {
    return this.api.post<SupplierResponse>('/api/admin/suppliers', request);
  }

  /** Update an existing supplier. */
  update(id: number, request: SupplierRequest): Observable<SupplierResponse> {
    return this.api.put<SupplierResponse>(`/api/admin/suppliers/${id}`, request);
  }

  /** Re-activate a supplier. */
  activate(id: number): Observable<SupplierResponse> {
    return this.api.post<SupplierResponse>(`/api/admin/suppliers/${id}/activate`, {});
  }

  /** Deactivate a supplier (hidden from PO supplier pickers). */
  deactivate(id: number): Observable<SupplierResponse> {
    return this.api.post<SupplierResponse>(`/api/admin/suppliers/${id}/deactivate`, {});
  }
}
