import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** A team lead with the count of salespeople currently assigned to them. */
export interface TeamLead {
  id: number;
  fullName: string;
  username: string;
  active: boolean;
  memberCount: number;
}

/** A salesperson row with their current team-lead assignment (null = unassigned). */
export interface TeamMember {
  id: number;
  fullName: string;
  username: string;
  active: boolean;
  teamLeadId: number | null;
  teamLeadName: string | null;
}

/**
 * Data access for ADMIN-only team management ({@code /api/admin/team}).
 *
 * <p>Lets an admin view the team leads and every salesperson's assignment, and
 * (re)assign a salesperson to a team lead — which drives the team-scoped order
 * visibility a TEAM_LEAD gets. Calls go through the shared {@link ApiClient}.
 */
@Injectable({ providedIn: 'root' })
export class TeamService {
  private readonly api = inject(ApiClient);

  /** All team leads with their member counts. */
  leads(): Observable<TeamLead[]> {
    return this.api.get<TeamLead[]>('/api/admin/team/leads');
  }

  /** Every salesperson with their current team-lead assignment. */
  salespeople(): Observable<TeamMember[]> {
    return this.api.get<TeamMember[]>('/api/admin/team/salespeople');
  }

  /** Assigns a salesperson to a team lead, or clears it when teamLeadId is null. */
  assign(salespersonId: number, teamLeadId: number | null): Observable<void> {
    return this.api.put<void>(`/api/admin/team/salespeople/${salespersonId}`, { teamLeadId });
  }
}
