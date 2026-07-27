import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** Headline metrics for one salesperson (mirrors backend SalespersonPerformanceSummary). */
export interface TeamMemberPerformance {
  id: number;
  username: string;
  fullName: string;
  active: boolean;
  verificationStatus: string | null;
  ordersTotal: number;
  ordersThisMonth: number;
  ordersToday: number;
  revenueTotal: string;
  revenueThisMonth: string;
  deliveredCount: number;
  failedCount: number;
  successRate: number;
  codOutstanding: string;
}

/** Per-source lead conversion for the team (mirrors backend TeamSourceConversion). */
export interface TeamSourceConversion {
  source: string;
  leads: number;
  won: number;
  conversionRate: number;
}

/** The team-lead performance rollup (mirrors backend TeamPerformanceResponse). */
export interface TeamPerformance {
  memberCount: number;
  ordersTotal: number;
  ordersThisMonth: number;
  revenueTotal: string;
  revenueThisMonth: string;
  delivered: number;
  failed: number;
  deliverySuccessRate: number;
  codOutstanding: string;
  leadsTotal: number;
  leadsWon: number;
  leadConversionRate: number;
  topPerformerName: string | null;
  topSource: string | null;
  leaderboard: TeamMemberPerformance[];
  leadSources: TeamSourceConversion[];
}

/**
 * Data access for the team-lead performance dashboard
 * ({@code GET /api/team/performance}). A TEAM_LEAD sees their own team; an ADMIN
 * sees the whole sales force. The team is resolved server-side from the caller.
 */
@Injectable({ providedIn: 'root' })
export class TeamPerformanceService {
  private readonly api = inject(ApiClient);

  performance(): Observable<TeamPerformance> {
    return this.api.get<TeamPerformance>('/api/team/performance');
  }
}
