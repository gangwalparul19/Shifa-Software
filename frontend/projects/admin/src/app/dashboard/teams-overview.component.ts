import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { TeamMemberPerformance } from '../team/team-performance.service';
import { TeamMemberDetailComponent } from '../team/team-member-detail.component';
import { TeamsOverviewService } from './teams-overview.service';
import { TeamCallOut, TeamOverviewMember, TeamOverviewRow, TeamsOverviewResponse } from './teams-overview.model';

/** Ordered lead pipeline stages (mirrors the leads feature's LEAD_STAGE_ORDER, active stages only). */
const PIPELINE_STAGES: { key: string; label: string }[] = [
  { key: 'NEW', label: 'New' },
  { key: 'CONTACTED', label: 'Contacted' },
  { key: 'QUOTED', label: 'Quoted' },
];

/**
 * "Team-wise sales with status" — a full admin-only page showing every team
 * lead's team ("Team Sameer", "Team Zeeshan", ...) side by side: combined
 * orders/revenue/delivery KPIs this month, the current lead pipeline, and a
 * ranked call-out list of leads due or overdue for a follow-up, each with a
 * one-tap call/WhatsApp action — so an admin can see where each team is
 * heading and act on it without opening each team lead's own dashboard.
 */
@Component({
  selector: 'admin-teams-overview',
  standalone: true,
  imports: [PageHeaderComponent, StatePanelComponent, RouterLink, TeamMemberDetailComponent],
  templateUrl: './teams-overview.component.html',
  styleUrl: './teams-overview.component.css',
})
export class TeamsOverviewComponent implements OnInit {
  private readonly service = inject(TeamsOverviewService);

  protected readonly data = signal<TeamsOverviewResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly pipelineStages = PIPELINE_STAGES;

  /** The team card currently drilled into (opens a full-team detail panel), or null. */
  protected readonly selectedTeam = signal<TeamOverviewRow | null>(null);
  /** The salesperson drilled into FROM the team panel (opens the existing 360 drawer on top). */
  protected readonly selectedMember = signal<TeamMemberPerformance | null>(null);

  /** Every row (teams + the unassigned bucket, when present) for uniform rendering. */
  protected readonly rows = computed<TeamOverviewRow[]>(() => {
    const d = this.data();
    if (!d) {
      return [];
    }
    return d.unassigned ? [...d.teams, d.unassigned] : d.teams;
  });

  /** Company-wide totals across every team, for a header strip. */
  protected readonly totals = computed(() => {
    const rows = this.rows();
    return rows.reduce(
      (acc, r) => ({
        orders: acc.orders + r.ordersThisMonth,
        revenue: acc.revenue + Number(r.revenueThisMonth || 0),
        dueFollowUps: acc.dueFollowUps + r.dueFollowUps,
      }),
      { orders: 0, revenue: 0, dueFollowUps: 0 },
    );
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.overview().subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the teams overview. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** "Team Sameer" style heading from a team lead's first name (falls back to the full name). */
  teamHeading(row: TeamOverviewRow): string {
    if (row.teamLeadId === null) {
      return row.teamLeadName;
    }
    const first = (row.teamLeadName || '').trim().split(/\s+/)[0];
    return first ? `Team ${first}` : row.teamLeadName;
  }

  /** A colour class for the delivery-success badge: green ≥80%, amber ≥50%, else red. */
  successClass(rate: number | null): string {
    if (rate === null) {
      return 'bg-secondary-lt';
    }
    if (rate >= 80) return 'bg-green-lt';
    if (rate >= 50) return 'bg-yellow-lt';
    return 'bg-red-lt';
  }

  pipelineCount(row: TeamOverviewRow, key: string): number {
    return row.leadPipeline?.[key] ?? 0;
  }

  money(value: string | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
  }

  /** Opens the caller's phone dialer for a call-out. */
  callHref(mobile: string | null): string {
    return mobile ? `tel:${mobile}` : '';
  }

  /** Opens WhatsApp for a call-out lead with a friendly follow-up nudge. */
  waCallOut(c: TeamCallOut): void {
    if (!c.customerMobile) {
      return;
    }
    openWhatsApp(c.customerMobile, whatsAppMessage('followup', { customerName: c.customerName }));
  }

  trackTeam(_: number, row: TeamOverviewRow): string {
    return row.teamLeadId === null ? 'unassigned' : String(row.teamLeadId);
  }

  trackCallOut(_: number, c: TeamCallOut): number {
    return c.leadId;
  }

  trackMember(_: number, m: TeamOverviewMember): number {
    return m.id;
  }

  /** Drill into a team card: opens a full-team detail panel on the same page. */
  openTeam(row: TeamOverviewRow): void {
    this.selectedTeam.set(row);
  }

  closeTeam(): void {
    this.selectedTeam.set(null);
  }

  /** From inside a team's detail panel, drill further into one salesperson. */
  openMember(member: TeamOverviewMember): void {
    // The 360 drawer expects the same TeamMemberPerformance shape used on the
    // Team Performance page; the two DTOs are structurally identical.
    this.selectedMember.set(member as unknown as TeamMemberPerformance);
  }

  closeMember(): void {
    this.selectedMember.set(null);
  }
}
