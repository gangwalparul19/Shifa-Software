import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { TeamsOverviewResponse } from './teams-overview.model';

/**
 * Data access for the "Team-wise sales with status" admin dashboard view
 * ({@code GET /api/admin/dashboard/teams}, ADMIN only).
 */
@Injectable({ providedIn: 'root' })
export class TeamsOverviewService {
  private readonly api = inject(ApiClient);

  overview(): Observable<TeamsOverviewResponse> {
    return this.api.get<TeamsOverviewResponse>('/api/admin/dashboard/teams');
  }
}
