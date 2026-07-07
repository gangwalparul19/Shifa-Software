import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import { ExpenseRequest, ExpenseResponse } from './expenses.model';

/** Filters + paging for the admin expenses listing (server-side). */
export interface ExpensePageQuery {
  category?: string | null;
  /** Inclusive lower bound, yyyy-MM-dd. */
  from?: string | null;
  /** Inclusive upper bound, yyyy-MM-dd. */
  to?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "incurredOn,desc"). */
  sort?: string | null;
}

/**
 * Data access for admin Expenses ({@code /api/admin/expenses}, ADMIN +
 * ACCOUNTANT). Calls go through the shared {@link ApiClient}; the auth
 * interceptor attaches the bearer token and the backend enforces the roles.
 */
@Injectable({ providedIn: 'root' })
export class ExpensesService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered, sorted expenses. */
  page(query: ExpensePageQuery): Observable<PageResponse<ExpenseResponse>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    const category = query.category?.trim();
    if (category) {
      params['category'] = category;
    }
    if (query.from) {
      params['from'] = query.from;
    }
    if (query.to) {
      params['to'] = query.to;
    }
    if (query.sort) {
      params['sort'] = query.sort;
    }
    return this.api.get<PageResponse<ExpenseResponse>>('/api/admin/expenses', { params });
  }

  /** A single expense by id. */
  get(id: number): Observable<ExpenseResponse> {
    return this.api.get<ExpenseResponse>(`/api/admin/expenses/${id}`);
  }

  /** Record a new expense. */
  create(request: ExpenseRequest): Observable<ExpenseResponse> {
    return this.api.post<ExpenseResponse>('/api/admin/expenses', request);
  }

  /** Delete an expense. */
  delete(id: number): Observable<void> {
    return this.api.delete<void>(`/api/admin/expenses/${id}`);
  }
}
