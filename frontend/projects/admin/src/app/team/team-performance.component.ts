import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { AuthService, Role } from 'core';
import { PageHeaderComponent, } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import {
  TeamCoachingFlag,
  TeamMemberPerformance,
  TeamPerformance,
  TeamPerformanceService,
} from './team-performance.service';
import { TeamMemberDetailComponent } from './team-member-detail.component';

const SOURCE_LABELS: Record<string, string> = {
  WHATSAPP: 'WhatsApp',
  INSTAGRAM: 'Instagram',
  FACEBOOK: 'Facebook',
  GOOGLE: 'Google',
  OFFLINE: 'Offline',
  OTHER: 'Other',
};

type PeriodKey = 'today' | 'week' | 'month' | 'custom';
type HealthFilter = 'ALL' | 'INACTIVE' | 'NO_ACTIVITY' | 'FOLLOW_UPS' | 'RTO';

@Component({
  selector: 'admin-team-performance',
  imports: [RouterLink, DatePipe, PageHeaderComponent, StatePanelComponent, TeamMemberDetailComponent],
  styleUrl: './team-performance.component.css',
  template: `
    <admin-page-header
      title="Team Performance"
      subtitle="Track sales, pipeline, delivery, collections, and coaching priorities for your team."
      [breadcrumbs]="[{ label: 'Team Performance' }]"
    >
      <button type="button" class="btn btn-outline-primary" (click)="downloadCsv()" [disabled]="loading() || !data()">
        <i class="ti ti-download me-1"></i> Management CSV
      </button>
      <button type="button" class="btn btn-outline-secondary" (click)="printReport()" [disabled]="loading() || !data()">
        <i class="ti ti-printer me-1"></i> Print / PDF
      </button>
      <button type="button" class="btn btn-icon btn-outline-secondary" (click)="load()" [disabled]="loading()" title="Refresh" aria-label="Refresh">
        <i class="ti ti-refresh"></i>
      </button>
    </admin-page-header>

    <div class="card mb-3">
      <div class="card-body py-2 d-flex flex-wrap align-items-center gap-2">
        <span class="text-secondary small fw-medium"><i class="ti ti-calendar-event me-1"></i>Reporting period</span>
        <div class="btn-group btn-group-sm" role="group" aria-label="Reporting period">
          @for (option of periodOptions; track option.key) {
            <button type="button" class="btn" [class.btn-primary]="period() === option.key" [class.btn-outline-secondary]="period() !== option.key" (click)="applyPeriod(option.key)">
              {{ option.label }}
            </button>
          }
        </div>
        @if (period() === 'custom') {
          <input type="date" class="form-control form-control-sm team-period-input" [value]="customFrom()" (change)="customFrom.set($any($event.target).value); load()" aria-label="Period from" />
          <span class="text-secondary small">to</span>
          <input type="date" class="form-control form-control-sm team-period-input" [value]="customTo()" (change)="customTo.set($any($event.target).value); load()" aria-label="Period to" />
        }
        @if (data()?.period; as p) {
          <span class="text-secondary small ms-auto">{{ p.from }} → {{ p.to }}</span>
        }
      </div>
    </div>

    @if (loading()) {
      <admin-state-panel variant="loading" [card]="true" loadingLabel="Loading team performance…" />
    } @else if (loadError()) {
      <admin-state-panel variant="error" [card]="true" [message]="loadError()!" (retry)="load()" />
    } @else if (data(); as d) {
      @if (d.memberCount === 0) {
        <div class="alert alert-info" role="status">
          <i class="ti ti-info-circle me-1"></i>
          No salespeople are assigned to you yet. An admin can assign your team on the Teams page.
        </div>
      } @else {
        @if (d.period; as p) {
          <div class="row row-cards g-2 g-md-3 mb-3">
            <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#206bc4;--accent-soft:#e7f0fb"><div class="card-body"><div class="subheader">Revenue</div><div class="h2 m-0">{{ inr(p.revenue) }}</div><div class="text-secondary small">{{ p.orders }} orders · AOV {{ inr(p.averageOrderValue) }}</div></div></div></div>
            <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#1f5d3f;--accent-soft:#e4f2ea"><div class="card-body"><div class="subheader">Today</div><div class="h2 m-0">{{ inr(p.revenueToday) }}</div><div class="text-secondary small">{{ p.ordersToday }} orders</div></div></div></div>
            <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#f59f00;--accent-soft:#fdf1da"><div class="card-body"><div class="subheader">Revenue vs previous</div><div class="h2 m-0" [class.text-success]="delta(p.revenue, p.previousRevenue) >= 0" [class.text-danger]="delta(p.revenue, p.previousRevenue) < 0">{{ delta(p.revenue, p.previousRevenue) }}%</div><div class="text-secondary small">{{ inr(p.previousRevenue) }} previous</div></div></div></div>
            <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#7950f2;--accent-soft:#eeeaff"><div class="card-body"><div class="subheader">Target progress</div><div class="h2 m-0">{{ p.targetProgressPct === null ? '—' : p.targetProgressPct + '%' }}</div><div class="text-secondary small">{{ p.target === '0' ? 'No target set' : inr(p.target) + ' target' }}</div></div></div></div>
          </div>
        }

        @if (isTeamLead() && d.ownPeriod; as own) {
          <div class="card mb-3 border-success">
            <div class="card-header py-2"><span class="fw-medium"><i class="ti ti-user me-1"></i>My performance</span><span class="text-secondary small">Your punched orders</span></div>
            <div class="card-body py-2">
              <div class="row g-2">
                <div class="col-6 col-md-3"><div class="p-2 rounded border"><strong>{{ inr(own.revenue) }}</strong><div class="text-secondary small">Revenue · {{ own.orders }} orders</div></div></div>
                <div class="col-6 col-md-3"><div class="p-2 rounded border"><strong>{{ inr(own.averageOrderValue) }}</strong><div class="text-secondary small">Average order value</div></div></div>
                <div class="col-6 col-md-3"><div class="p-2 rounded border"><strong>{{ inr(own.pendingPaymentAmount) }}</strong><div class="text-secondary small">My pending collection</div></div></div>
                <div class="col-6 col-md-3"><div class="p-2 rounded border"><strong>{{ own.followUpsDue }}</strong><div class="text-secondary small">My follow-ups due</div></div></div>
              </div>
            </div>
          </div>
        }

        <div class="row row-cards g-2 g-md-3 mb-3">
          <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#d63939;--accent-soft:#fbe7e7"><div class="card-body"><div class="subheader">Pending collections</div><div class="h2 m-0">{{ inr(d.period?.pendingPaymentAmount) }}</div><div class="text-secondary small">{{ d.period?.pendingPaymentCount }} orders</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#f76707;--accent-soft:#fdeade"><div class="card-body"><div class="subheader">RTO / redispatch</div><div class="h2 m-0">{{ d.period?.rto ?? 0 }}</div><div class="text-secondary small">{{ d.period?.failed ?? 0 }} failed outcomes</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#ae3ec9;--accent-soft:#f6e6fa"><div class="card-body"><div class="subheader">Follow-ups due</div><div class="h2 m-0">{{ d.period?.followUpsDue ?? 0 }}</div><div class="text-secondary small">Due or overdue</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat-accent" style="--accent:#206bc4;--accent-soft:#e7f0fb"><div class="card-body"><div class="subheader">Needs attention</div><div class="h2 m-0">{{ d.coachingFlags.length }}</div><div class="text-secondary small">Explainable coaching flags</div></div></div></div>
        </div>

        <div class="card mb-3">
          <div class="card-header d-flex align-items-center justify-content-between"><span class="fw-medium"><i class="ti ti-checklist me-1"></i>Today's work</span><span class="text-secondary small">Open the owning page to act</span></div>
          <div class="card-body py-2">
            <div class="row g-2">
              <a class="col-6 col-md-3 text-reset text-decoration-none" routerLink="/approval-queue"><div class="p-2 rounded border"><strong>{{ d.work?.pendingApproval ?? 0 }}</strong><div class="text-secondary small">Approval pending</div></div></a>
              <a class="col-6 col-md-3 text-reset text-decoration-none" routerLink="/payments"><div class="p-2 rounded border"><strong>{{ d.work?.pendingPaymentVerification ?? 0 }}</strong><div class="text-secondary small">Payment verification</div></div></a>
              <a class="col-6 col-md-3 text-reset text-decoration-none" routerLink="/leads/follow-ups"><div class="p-2 rounded border"><strong>{{ d.work?.followUpsDue ?? 0 }}</strong><div class="text-secondary small">Follow-ups due</div></div></a>
              <a class="col-6 col-md-3 text-reset text-decoration-none" routerLink="/orders"><div class="p-2 rounded border"><strong>{{ d.work?.failedDelivery ?? 0 }}</strong><div class="text-secondary small">Failed deliveries</div></div></a>
            </div>
          </div>
        </div>

        <div class="card mb-3">
          <div class="card-header d-flex align-items-center justify-content-between"><span class="fw-medium"><i class="ti ti-receipt me-1"></i>Order ownership</span><span class="text-secondary small">Salesperson name is shown for every team order</span></div>
          <div class="card-body py-2">
            @if (isTeamLead()) {
              <div class="btn-group btn-group-sm mb-2" role="tablist">
                <button type="button" class="btn" [class.btn-primary]="orderTab() === 'own'" [class.btn-outline-secondary]="orderTab() !== 'own'" (click)="orderTab.set('own')">My orders ({{ d.ownOrders.length }})</button>
                <button type="button" class="btn" [class.btn-primary]="orderTab() === 'team'" [class.btn-outline-secondary]="orderTab() !== 'team'" (click)="orderTab.set('team')">Team orders ({{ d.teamOrders.length }})</button>
              </div>
            }
            @if (orderTab() === 'own' && isTeamLead()) {
              @if (d.ownOrders.length === 0) { <div class="text-secondary small py-2">No personal orders in this period.</div> } @else {
                <div class="table-responsive"><table class="table table-sm table-vcenter"><thead><tr><th>Order</th><th>Customer</th><th class="text-end">Amount</th><th>Status</th><th>Date</th></tr></thead><tbody>@for (order of d.ownOrders; track order.id) { <tr><td><a class="shifa-mono" [routerLink]="['/orders']" [queryParams]="{ q: order.orderCode }">{{ order.orderCode }}</a></td><td>{{ order.customerName }}</td><td class="text-end">{{ inr(order.totalAmount) }}</td><td>{{ statusLabel(order.orderStatus) }}</td><td>{{ order.createdAt | date:'dd MMM, HH:mm' }}</td></tr> }</tbody></table></div>
              }
            } @else {
              @if (d.teamOrders.length === 0) { <div class="text-secondary small py-2">No teammate orders in this period.</div> } @else {
                <div class="table-responsive"><table class="table table-sm table-vcenter"><thead><tr><th>Order</th><th>Salesperson</th><th>Customer</th><th class="text-end">Amount</th><th>Status</th><th>Date</th></tr></thead><tbody>@for (order of d.teamOrders; track order.id) { <tr><td><a class="shifa-mono" [routerLink]="['/orders']" [queryParams]="{ q: order.orderCode }">{{ order.orderCode }}</a></td><td class="fw-medium">{{ order.salespersonName }}</td><td>{{ order.customerName }}</td><td class="text-end">{{ inr(order.totalAmount) }}</td><td>{{ statusLabel(order.orderStatus) }}</td><td>{{ order.createdAt | date:'dd MMM, HH:mm' }}</td></tr> }</tbody></table></div>
              }
            }
          </div>
        </div>

        <div class="row g-2 g-md-3 mb-3">
          <div class="col-12 col-md-6"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3"><span class="stat-icon" style="--accent:#1f5d3f;--accent-soft:#e4f2ea"><i class="ti ti-user-star"></i></span><div><div class="subheader">Top performer</div><div class="fw-medium">{{ d.topPerformerName ?? '—' }}</div><div class="text-secondary small">Selected-period revenue leader</div></div></div></div></div>
          <div class="col-12 col-md-6"><div class="card h-100"><div class="card-body d-flex align-items-center gap-3"><span class="stat-icon" style="--accent:#206bc4;--accent-soft:#e7f0fb"><i class="ti ti-trophy"></i></span><div><div class="subheader">Best-converting source</div><div class="fw-medium">{{ d.topSource ? sourceLabel(d.topSource) : '—' }}</div><div class="text-secondary small">Across selected-period team leads</div></div></div></div></div>
        </div>

        <div class="card mb-3" id="team-members">
          <div class="card-header d-flex flex-wrap align-items-center gap-2">
            <span class="fw-medium me-auto">Salespeople ({{ d.memberCount }})</span>
            <input class="form-control form-control-sm team-member-search" type="search" placeholder="Search name…" [value]="search()" (input)="search.set($any($event.target).value)" aria-label="Search team members" />
            <select class="form-select form-select-sm team-member-filter" [value]="healthFilter()" (change)="healthFilter.set($any($event.target).value)">
              <option value="ALL">All members</option><option value="INACTIVE">Inactive</option><option value="NO_ACTIVITY">No activity</option><option value="FOLLOW_UPS">Follow-ups due</option><option value="RTO">RTO / redispatch</option>
            </select>
          </div>
          <div class="table-responsive">
            <table class="table table-vcenter card-table">
              <thead><tr><th>Salesperson</th><th>Status</th><th class="text-end">Orders</th><th class="text-end">Revenue</th><th class="text-end">AOV</th><th class="text-end">Delivery</th><th class="text-end">RTO</th><th class="text-end">Follow-ups</th><th class="text-end">Outstanding</th></tr></thead>
              <tbody>
                @for (m of filteredMembers(); track m.id) {
                  <tr class="shifa-card-link" role="button" tabindex="0" (click)="openMember(m)" (keydown.enter)="openMember(m)" (keydown.space)="$event.preventDefault(); openMember(m)" title="View {{ m.fullName }} detail">
                    <td><div class="fw-medium">{{ m.fullName }}</div><div class="text-secondary small">{{ '@' + m.username }}</div></td>
                    <td><span class="badge" [class.tone-green]="m.active && m.ordersInPeriod > 0" [class.tone-amber]="m.active && m.ordersInPeriod === 0" [class.tone-red]="!m.active">{{ memberHealth(m) }}</span></td>
                    <td class="text-end">{{ m.ordersInPeriod }}<div class="text-secondary small">{{ m.ordersToday }} today</div></td>
                    <td class="text-end">{{ inr(m.revenueInPeriod) }}</td>
                    <td class="text-end">{{ inr(m.averageOrderValue) }}</td>
                    <td class="text-end">{{ m.successRate }}%</td>
                    <td class="text-end">{{ m.rtoCount }}</td>
                    <td class="text-end" [class.text-danger]="m.dueFollowUps > 0">{{ m.dueFollowUps }}</td>
                    <td class="text-end">{{ inr(m.codOutstanding) }}</td>
                  </tr>
                } @empty { <tr><td colspan="9" class="text-secondary text-center py-4">No team members match this filter.</td></tr> }
              </tbody>
            </table>
          </div>
        </div>

        @if (d.coachingFlags.length > 0) {
          <div class="card mb-3"><div class="card-header"><span class="fw-medium"><i class="ti ti-school me-1"></i>Coaching priorities</span></div><div class="list-group list-group-flush">
            @for (flag of d.coachingFlags; track flag.salespersonId + ':' + flag.type) {
              <button type="button" class="list-group-item list-group-item-action text-start" (click)="openFlag(flag, d.leaderboard)"><div class="d-flex align-items-start gap-2"><span class="badge" [class.tone-red]="flag.severity === 'HIGH'" [class.tone-amber]="flag.severity === 'WARNING'" [class.tone-blue]="flag.severity === 'INFO'">{{ flag.severity }}</span><div><div class="fw-medium">{{ flag.salespersonName }} · {{ flag.title }}</div><div class="text-secondary small">{{ flag.detail }}</div></div></div></button>
            }
          </div></div>
        }

        <div class="card"><div class="card-header py-2"><span class="fw-medium">Lead source conversion</span></div>
          @if (d.leadSources.length === 0) { <div class="card-body text-secondary">No leads captured by your team yet.</div> } @else { <div class="table-responsive"><table class="table table-vcenter card-table"><thead><tr><th>Source</th><th class="text-end">Leads</th><th class="text-end">Won</th><th class="text-end">Conversion</th></tr></thead><tbody>@for (s of d.leadSources; track s.source) { <tr><td class="fw-medium">{{ sourceLabel(s.source) }}</td><td class="text-end">{{ s.leads }}</td><td class="text-end">{{ s.won }}</td><td class="text-end"><span class="badge" [class.tone-green]="s.conversionRate >= 50" [class.tone-amber]="s.conversionRate < 50">{{ s.conversionRate }}%</span></td></tr> }</tbody></table></div> }
        </div>
      }
    }
    @if (selectedMember(); as member) { <admin-team-member-detail [member]="member" (closed)="closeMember()" /> }
  `,
})
export class TeamPerformanceComponent implements OnInit {
  private readonly service = inject(TeamPerformanceService);
  private readonly auth = inject(AuthService);

  protected readonly data = signal<TeamPerformance | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly selectedMember = signal<TeamMemberPerformance | null>(null);
  protected readonly orderTab = signal<'own' | 'team'>('team');
  protected readonly isTeamLead = computed(() => this.auth.role() === Role.TEAM_LEAD);
  protected readonly period = signal<PeriodKey>('month');
  protected readonly customFrom = signal('');
  protected readonly customTo = signal('');
  protected readonly search = signal('');
  protected readonly healthFilter = signal<HealthFilter>('ALL');
  protected readonly periodOptions: { key: PeriodKey; label: string }[] = [
    { key: 'today', label: 'Today' },
    { key: 'week', label: 'Last 7 days' },
    { key: 'month', label: 'This month' },
    { key: 'custom', label: 'Custom' },
  ];

  protected readonly filteredMembers = computed(() => {
    const query = this.search().trim().toLowerCase();
    const filter = this.healthFilter();
    return (this.data()?.leaderboard ?? []).filter((member) => {
      const matchesSearch = !query || member.fullName.toLowerCase().includes(query) || member.username.toLowerCase().includes(query);
      const matchesFilter = filter === 'ALL'
        || (filter === 'INACTIVE' && !member.active)
        || (filter === 'NO_ACTIVITY' && member.active && member.ordersInPeriod === 0)
        || (filter === 'FOLLOW_UPS' && member.dueFollowUps > 0)
        || (filter === 'RTO' && member.rtoCount > 0);
      return matchesSearch && matchesFilter;
    });
  });

  ngOnInit(): void { this.load(); }

  applyPeriod(key: PeriodKey): void {
    this.period.set(key);
    if (key === 'custom') {
      const today = new Date();
      this.customTo.set(today.toISOString().slice(0, 10));
      this.customFrom.set(new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0, 10));
    }
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const dates = this.window();
    this.service.performance(dates.from, dates.to).subscribe({
      next: (res) => { this.data.set(res); this.loading.set(false); },
      error: () => { this.loadError.set('Could not load team performance. Please try again.'); this.loading.set(false); },
    });
  }

  private window(): { from?: string; to?: string } {
    const today = new Date();
    const to = today.toISOString().slice(0, 10);
    if (this.period() === 'today') return { from: to, to };
    if (this.period() === 'week') {
      const from = new Date(today); from.setDate(today.getDate() - 6);
      return { from: from.toISOString().slice(0, 10), to };
    }
    if (this.period() === 'custom') return { from: this.customFrom(), to: this.customTo() };
    return { from: new Date(today.getFullYear(), today.getMonth(), 1).toISOString().slice(0, 10), to };
  }

  protected openMember(member: TeamMemberPerformance): void { this.selectedMember.set(member); }
  protected openFlag(flag: TeamCoachingFlag, members: TeamMemberPerformance[]): void {
    const member = members.find((row) => row.id === flag.salespersonId);
    if (member) this.openMember(member);
  }
  protected closeMember(): void { this.selectedMember.set(null); }
  sourceLabel(source: string): string { return SOURCE_LABELS[source] ?? source; }
  inr(amount: string | number | null | undefined): string { return `₹${Number(amount ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`; }
  delta(current: string | number | null | undefined, previous: string | number | null | undefined): number {
    const c = Number(current ?? 0); const p = Number(previous ?? 0);
    return p === 0 ? (c === 0 ? 0 : 100) : Math.round(((c - p) / p) * 100);
  }
  statusLabel(value: string | null | undefined): string {
    return (value || '—').toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  }
  memberHealth(member: TeamMemberPerformance): string {
    if (!member.active) return 'Inactive';
    if (member.ordersInPeriod === 0) return 'No activity';
    if (member.dueFollowUps > 3) return 'Follow-ups';
    if (member.rtoCount > 0) return 'Review delivery';
    return 'On track';
  }

  printReport(): void { window.print(); }

  downloadCsv(): void {
    const d = this.data();
    if (!d) return;
    const rows = [
      ['Salesperson', 'Username', 'Status', 'Orders in period', 'Revenue in period', 'AOV', 'Delivery success', 'RTO/Redispatch', 'Follow-ups due', 'Outstanding'],
      ...d.leaderboard.map((m) => [m.fullName, m.username, this.memberHealth(m), String(m.ordersInPeriod), m.revenueInPeriod, m.averageOrderValue, `${m.successRate}%`, String(m.rtoCount), String(m.dueFollowUps), m.codOutstanding]),
    ];
    const csv = rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a'); a.href = url; a.download = `team-performance-${this.period()}.csv`; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
