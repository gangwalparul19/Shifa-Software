import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatInr } from '../shared/inr.pipe';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import { PeriodSelection, THIS_MONTH, isUsableSelection } from '../shared/period-selection';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DialogDirective } from '../shared/dialog.directive';
import { ToastService } from '../shared/toast.service';
import { saveBlob } from '../shared/blob-download.util';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { TeamMemberPerformance } from '../team/team-performance.service';
import { TeamMemberDetailComponent } from '../team/team-member-detail.component';
import {
  TeamsDownload,
  TeamsExportFormat,
  TeamsExportScope,
  TeamsOverviewService,
} from './teams-overview.service';
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
 * orders/revenue/delivery KPIs for the chosen period, the current lead pipeline,
 * and a ranked call-out list of leads due or overdue for a follow-up, each with a
 * one-tap call/WhatsApp action — so an admin can see where each team is
 * heading and act on it without opening each team lead's own dashboard.
 *
 * <p>The period (Today, Yesterday, Last 7 days, This month, Last month, Overall or a custom
 * range) is chosen with the shared picker and drives the orders and revenue; the whole report, or
 * one team, can be downloaded as Excel or PDF for the same period.
 */
@Component({
  selector: 'admin-teams-overview',
  standalone: true,
  imports: [
    PageHeaderComponent,
    StatePanelComponent,
    RouterLink,
    TeamMemberDetailComponent,
    DialogDirective,
    PeriodPickerComponent,
  ],
  templateUrl: './teams-overview.component.html',
  styleUrl: './teams-overview.component.css',
})
export class TeamsOverviewComponent implements OnInit {
  private readonly service = inject(TeamsOverviewService);
  private readonly toasts = inject(ToastService);

  protected readonly data = signal<TeamsOverviewResponse | null>(null);
  /** True while the first load is running and there is nothing to show yet. */
  protected readonly loading = signal(true);
  /** True while a later load (a new period) is running; the previous figures stay visible, dimmed. */
  protected readonly refreshing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pipelineStages = PIPELINE_STAGES;

  /** The chosen period (this month to start with). */
  protected readonly selection = signal<PeriodSelection>(THIS_MONTH);
  /** Which download is running ("xlsx" / "pdf"), or null. */
  protected readonly exporting = signal<TeamsExportFormat | null>(null);
  /** Numbers each load so a slow answer for an earlier period never overwrites a newer one. */
  private requestId = 0;

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
        orders: acc.orders + r.ordersInPeriod,
        revenue: acc.revenue + Number(r.revenueInPeriod || 0),
        dueFollowUps: acc.dueFollowUps + r.dueFollowUps,
      }),
      { orders: 0, revenue: 0, dueFollowUps: 0 },
    );
  });

  /** "This Month", "Yesterday", "2026-10-01 to 2026-10-10" ... as the server resolved it. */
  protected readonly periodText = computed(() => this.data()?.periodLabel ?? '');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      return;
    }
    const id = ++this.requestId;
    if (this.data() === null) {
      this.loading.set(true);
    } else {
      this.refreshing.set(true);
    }
    this.error.set(null);
    this.service.overview(selection).subscribe({
      next: (d) => {
        if (id !== this.requestId) {
          return;
        }
        this.data.set(d);
        this.loading.set(false);
        this.refreshing.set(false);
        this.resyncOpenTeam(d);
      },
      error: () => {
        if (id !== this.requestId) {
          return;
        }
        // Never leave the old figures on screen under a new period's label.
        this.data.set(null);
        this.selectedTeam.set(null);
        this.error.set('Could not load the teams overview. Please try again.');
        this.loading.set(false);
        this.refreshing.set(false);
      },
    });
  }

  /** The admin picked another period: reload everything for it. */
  choosePeriod(selection: PeriodSelection): void {
    this.selection.set(selection);
    this.load();
  }

  /** Keeps an open team panel pointing at the freshly loaded figures of the same team. */
  private resyncOpenTeam(d: TeamsOverviewResponse): void {
    const open = this.selectedTeam();
    if (!open) {
      return;
    }
    const fresh =
      open.teamLeadId === null
        ? d.unassigned
        : (d.teams.find((t) => t.teamLeadId === open.teamLeadId) ?? null);
    this.selectedTeam.set(fresh);
  }

  /** Downloads the report for the chosen period: every team, or just the one passed in. */
  exportReport(format: TeamsExportFormat, team?: TeamOverviewRow): void {
    if (this.exporting()) {
      return;
    }
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      this.toasts.error('Choose a valid start and end date first.');
      return;
    }
    const scope: TeamsExportScope = team ? (team.teamLeadId === null ? 'unassigned' : team.teamLeadId) : 'all';
    this.exporting.set(format);
    this.service.exportReport(format, selection, scope).subscribe({
      next: (result: TeamsDownload) => {
        saveBlob(result.blob, result.filename, `team-wise-sales.${format}`);
        this.exporting.set(null);
        this.toasts.success(`Team-wise sales ${format.toUpperCase()} downloaded.`);
      },
      error: () => {
        this.exporting.set(null);
        this.toasts.error(`Could not download the team-wise sales ${format.toUpperCase()}.`);
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
    return formatInr(value, 0);
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
