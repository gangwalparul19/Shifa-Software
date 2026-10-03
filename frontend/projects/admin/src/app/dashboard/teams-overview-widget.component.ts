import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TeamsOverviewService } from './teams-overview.service';
import { TeamOverviewRow } from './teams-overview.model';

/**
 * Compact "Team-wise sales" card embedded on the admin dashboard: one row per
 * team (e.g. "Team Sameer", "Team Zeeshan") with this month's orders/revenue
 * and delivery-success status, plus a due-follow-ups badge — enough for an
 * admin to see at a glance where each team is heading, with a link through to
 * the full {@code /teams-overview} page for the call-out list and lead
 * pipeline detail. Non-fatal: hides itself if there are no teams yet or the
 * call fails, rather than cluttering the dashboard with an error.
 */
@Component({
  selector: 'admin-teams-overview-widget',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './teams-overview-widget.component.html',
  styleUrl: './teams-overview-widget.component.css',
})
export class TeamsOverviewWidgetComponent implements OnInit {
  private readonly service = inject(TeamsOverviewService);

  protected readonly rows = signal<TeamOverviewRow[]>([]);
  protected readonly loading = signal(true);

  protected readonly totalDueFollowUps = computed(() =>
    this.rows().reduce((sum, r) => sum + r.dueFollowUps, 0),
  );

  ngOnInit(): void {
    this.service.overview().subscribe({
      next: (d) => {
        const all = d.unassigned ? [...d.teams, d.unassigned] : d.teams;
        this.rows.set(all);
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.loading.set(false);
      },
    });
  }

  teamHeading(row: TeamOverviewRow): string {
    if (row.teamLeadId === null) {
      return row.teamLeadName;
    }
    const first = (row.teamLeadName || '').trim().split(/\s+/)[0];
    return first ? `Team ${first}` : row.teamLeadName;
  }

  successClass(rate: number | null): string {
    if (rate === null) {
      return 'bg-secondary-lt';
    }
    if (rate >= 80) return 'bg-green-lt';
    if (rate >= 50) return 'bg-yellow-lt';
    return 'bg-red-lt';
  }

  money(value: string | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
  }

  trackTeam(_: number, row: TeamOverviewRow): string {
    return row.teamLeadId === null ? 'unassigned' : String(row.teamLeadId);
  }
}
