import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** An admin-managed delivery state row (mirrors the backend {@code DeliveryStateResponse}). */
export interface DeliveryState {
  id: number;
  name: string;
  active: boolean;
  sortOrder: number;
}

/** Create/update payload for a delivery state (mirrors {@code DeliveryStateRequest}). */
export interface DeliveryStateRequest {
  name: string;
  active?: boolean;
  sortOrder?: number;
}

/**
 * Data access for the admin-managed delivery-state master list.
 *
 * <p>The order-entry state typeahead uses {@link activeNames} ({@code GET
 * /api/states}, any authenticated staff). The Settings management table uses the
 * ADMIN-only CRUD endpoints under {@code /api/admin/states}. All calls go through
 * the shared {@link ApiClient} so the auth interceptor attaches the bearer token.
 */
@Injectable({ providedIn: 'root' })
export class StatesService {
  private readonly api = inject(ApiClient);

  /** Active state names for the order-entry typeahead. */
  activeNames(): Observable<string[]> {
    return this.api.get<string[]>('/api/states');
  }

  /** All states (active + inactive) for the admin management table. */
  listAll(): Observable<DeliveryState[]> {
    return this.api.get<DeliveryState[]>('/api/admin/states');
  }

  /** Adds a new delivery state (ADMIN). */
  create(payload: DeliveryStateRequest): Observable<DeliveryState> {
    return this.api.post<DeliveryState>('/api/admin/states', payload);
  }

  /** Renames / re-orders / toggles a delivery state (ADMIN). */
  update(id: number, payload: DeliveryStateRequest): Observable<DeliveryState> {
    return this.api.put<DeliveryState>(`/api/admin/states/${id}`, payload);
  }

  /** Removes a delivery state (ADMIN). */
  delete(id: number): Observable<void> {
    return this.api.delete<void>(`/api/admin/states/${id}`);
  }
}
