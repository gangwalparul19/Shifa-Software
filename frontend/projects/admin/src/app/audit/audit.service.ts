import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, PageResponse } from 'core';
import { AuditEntry } from './audit.model';

/** Filters + paging for the admin audit log (server-side). */
export interface AuditPageQuery {
  action?: string | null;
  entityType?: string | null;
  q?: string | null;
  /** Inclusive lower bound, yyyy-MM-dd. */
  from?: string | null;
  /** Inclusive upper bound, yyyy-MM-dd. */
  to?: string | null;
  page?: number;
  size?: number;
  /** `field,dir` sort expression (e.g. "createdAt,desc"). */
  sort?: string | null;
}

/**
 * Data access for the admin Audit log ({@code /api/admin/audit}, ADMIN).
 * Calls go through the shared {@link ApiClient}; the auth interceptor attaches
 * the bearer token and the backend enforces the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly api = inject(ApiClient);

  /** Server-side paginated, filtered audit entries (newest first). */
  page(query: AuditPageQuery): Observable<PageResponse<AuditEntry>> {
    const params: Record<string, string | number> = {
      page: query.page ?? 0,
      size: query.size ?? 20,
    };
    if (query.action) {
      params['action'] = query.action;
    }
    if (query.entityType) {
      params['entityType'] = query.entityType;
    }
    const q = query.q?.trim();
    if (q) {
      params['q'] = q;
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
    return this.api.get<PageResponse<AuditEntry>>('/api/admin/audit', { params });
  }
}
