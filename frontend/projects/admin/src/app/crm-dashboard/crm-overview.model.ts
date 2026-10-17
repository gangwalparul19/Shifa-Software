import { FollowUpHealth, LeadStatus } from '../leads/leads.model';

/**
 * The unified CRM cockpit overview (CRM expansion Phase 2, Task 15; Req 14),
 * mirroring the backend `CrmOverviewResponse`. A thin composition of the lead
 * pipeline, follow-up health, monthly targets, and performance leaderboard.
 */

/** One monthly target-vs-achievement row (backend `SalesTargetRow`). */
export interface SalesTargetRow {
  salespersonId: number;
  salespersonName: string;
  active: boolean;
  month: string;
  targetAmount?: string | null;
  achieved?: string | null;
  orderCount: number;
  attainmentPct?: number | null;
  incentivePct?: string | null;
  incentiveAmount?: string | null;
  targetMet: boolean;
}

/** One salesperson performance headline row (backend `SalespersonPerformanceSummary`). */
export interface PerformanceRow {
  id: number;
  username: string;
  fullName: string;
  active: boolean;
  ordersThisMonth: number;
  revenueThisMonth?: string | null;
  deliveredCount: number;
  failedCount: number;
  successRate: number;
  dueFollowUps: number;
}

export interface CrmOverview {
  pipeline: Record<LeadStatus, number>;
  followUpHealth: FollowUpHealth;
  targets: SalesTargetRow[];
  leaderboard: PerformanceRow[];
}
