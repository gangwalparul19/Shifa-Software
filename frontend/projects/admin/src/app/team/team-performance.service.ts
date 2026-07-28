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

/** Safe, work-relevant profile information for a Team Lead's direct report. */
export interface DirectReportProfile {
  id: number;
  fullName: string;
  username: string;
  email: string | null;
  mobile: string | null;
  joinedOn: string | null;
  active: boolean;
  verificationStatus: string | null;
}

export interface SalespersonDailyPoint {
  date: string;
  orders: number;
  revenue: string;
}

export interface SalespersonOrderRow {
  orderCode: string;
  customerName: string;
  totalAmount: string;
  orderStatus: string;
  paymentStatus: string | null;
  createdAt: string;
}

export interface SalespersonLeadMetrics {
  total: number;
  won: number;
  lost: number;
  active: number;
  conversionRate: number;
  dueFollowUps: number;
}

/** Complete, team-scoped performance detail for one direct report. */
export interface DirectReportPerformance {
  profile: DirectReportProfile;
  performance: {
    summary: TeamMemberPerformance;
    trend: SalespersonDailyPoint[];
    recentOrders: SalespersonOrderRow[];
    leads: SalespersonLeadMetrics;
  };
}

/**
 * Data access for the team-lead performance dashboard. A TEAM_LEAD is scoped by
 * the server to assigned salespeople; an ADMIN sees the whole sales force.
 */
@Injectable({ providedIn: 'root' })
export class TeamPerformanceService {
  private readonly api = inject(ApiClient);

  performance(): Observable<TeamPerformance> {
    return this.api.get<TeamPerformance>('/api/team/performance');
  }

  /** Detailed performance for one authorised direct report (max 60 daily points). */
  detail(salespersonId: number, days = 60): Observable<DirectReportPerformance> {
    return this.api.get<DirectReportPerformance>(`/api/team/performance/${salespersonId}`, {
      params: { days },
    });
  }
}
