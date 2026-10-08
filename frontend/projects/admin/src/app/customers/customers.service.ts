import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import {
  CustomerDetail,
  CustomerNote,
  CustomerProfile,
  CustomerRisk,
  CustomerSummary,
} from './customers.model';

/** Filters + paging for the admin customers / CRM listing (server-side). */
export interface CustomerPageQuery {
  q?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "totalSpent,desc"). */
  sort?: string | null;
}

/**
 * Data access for the admin Customers / CRM view
 * ({@code /api/admin/customers}, ADMIN + ACCOUNTANT). Calls go through the
 * shared {@link ApiClient}; the auth interceptor attaches the bearer token and
 * the backend enforces the role restriction.
 */
@Injectable({ providedIn: 'root' })
export class CustomersService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, searchable, sorted customers. */
  page(query: CustomerPageQuery): Observable<PageResponse<CustomerSummary>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<CustomerSummary>>('/api/admin/customers', { params });
  }

  /** A single customer's summary + order history, keyed by mobile number. */
  detail(mobile: string): Observable<CustomerDetail> {
    return this.api.get<CustomerDetail>(`/api/admin/customers/${encodeURIComponent(mobile)}`);
  }

  // --- Customer 360 / CRM depth (FEATURE-ROADMAP §1) ----------------------

  /** The full Customer 360 profile: summary + metrics + risk + products + tags + notes + history. */
  profile(mobile: string): Observable<CustomerProfile> {
    return this.api.get<CustomerProfile>(
      `/api/admin/customers/${encodeURIComponent(mobile)}/profile`,
    );
  }

  /** A customer's delivery-reliability risk (used by the New Order nudge). Never 404s. */
  risk(mobile: string): Observable<CustomerRisk> {
    return this.api.get<CustomerRisk>(`/api/admin/customers/${encodeURIComponent(mobile)}/risk`);
  }

  /** Adds a note to the customer's timeline; returns the timeline (newest first). */
  addNote(mobile: string, note: string): Observable<CustomerNote[]> {
    return this.api.post<CustomerNote[]>(
      `/api/admin/customers/${encodeURIComponent(mobile)}/notes`,
      { note },
    );
  }

  /** Attaches a segment tag to the customer; returns the updated tag list. */
  addTag(mobile: string, tag: string): Observable<string[]> {
    return this.api.post<string[]>(
      `/api/admin/customers/${encodeURIComponent(mobile)}/tags`,
      { tag },
    );
  }

  /** Removes a segment tag from the customer; returns the updated tag list. */
  removeTag(mobile: string, tag: string): Observable<string[]> {
    return this.api.delete<string[]>(
      `/api/admin/customers/${encodeURIComponent(mobile)}/tags/${encodeURIComponent(tag)}`,
    );
  }
}
