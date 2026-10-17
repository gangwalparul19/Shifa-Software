import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { CrmOverview } from './crm-overview.model';

/**
 * Data access for the unified CRM cockpit (`/api/admin/crm/overview`, CRM
 * expansion Phase 2, Task 15). ADMIN-only; one call powers the dashboard.
 */
@Injectable({ providedIn: 'root' })
export class CrmOverviewService {
  private readonly api = inject(ApiClient);

  overview(): Observable<CrmOverview> {
    return this.api.get<CrmOverview>('/api/admin/crm/overview');
  }
}
