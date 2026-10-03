/**
 * Client-side models for the "Team-wise sales with status" admin dashboard
 * view (mirrors the backend {@code TeamsOverviewResponse} at
 * {@code GET /api/admin/dashboard/teams}).
 */

/** A lead worth calling right now: due or overdue for a follow-up. */
export interface TeamCallOut {
  leadId: number;
  customerName: string;
  customerMobile: string | null;
  leadSource: string | null;
  status: string;
  followUpDate: string | null;
  overdueDays: number;
  ownerName: string;
}

/** Headline performance metrics for one salesperson (mirrors backend SalespersonPerformanceSummary). */
export interface TeamOverviewMember {
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
  ordersInPeriod: number;
  revenueInPeriod: string;
  averageOrderValue: string;
  rtoCount: number;
  dueFollowUps: number;
}

/** One team's (or the unassigned bucket's) headline sales + lead-status snapshot. */
export interface TeamOverviewRow {
  teamLeadId: number | null;
  teamLeadName: string;
  memberCount: number;
  ordersTotal: number;
  ordersThisMonth: number;
  revenueTotal: string;
  revenueThisMonth: string;
  delivered: number;
  failed: number;
  deliverySuccessRate: number | null;
  codOutstanding: string;
  leadsTotal: number;
  leadsWon: number;
  leadsLost: number;
  leadConversionRate: number | null;
  leadPipeline: Record<string, number>;
  dueFollowUps: number;
  callOuts: TeamCallOut[];
  members: TeamOverviewMember[];
}

/** The full "Team-wise sales" overview payload. */
export interface TeamsOverviewResponse {
  asOf: string;
  teams: TeamOverviewRow[];
  unassigned: TeamOverviewRow | null;
}
