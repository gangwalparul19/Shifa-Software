import { Component, OnInit, inject, signal } from '@angular/core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { TeamPerformance, TeamPerformanceService } from './team-performance.service';

/** Friendly labels for the lead-source enum names. */
const SOURCE_LABELS: Record<string, string> = {
  WHATSAPP: 'WhatsApp',
  INSTAGRAM: 'Instagram',
  FACEBOOK: 'Facebook',
  GOOGLE: 'Google',
  OFFLINE: 'Offline',
  OTHER: 'Other',
};

/**
 * Team-lead performance dashboard: how the lead's team is doing at a glance —
 * headline KPIs (orders, revenue, delivery success, COD outstanding), the
 * best-converting lead source, a per-salesperson leaderboard, and full
 * lead-source conversion. Read-only. Admins see the whole sales force.
 */
@Component({
  selector: 'admin-team-performance',
  imports: [PageHeaderComponent, StatePanelComponent],
  template: `
    <admin-page-header
      title="Team Performance"
      subtitle="How your team is doing — orders, revenue, delivery and lead conversion."
      [breadcrumbs]="[{ label: 'Team Performance' }]"
    >
      <button type="button" class="btn btn-icon btn-outline-secondary" (click)="load()" [disabled]="loading()" title="Refresh" aria-label="Refresh">
        <i class="ti ti-refresh"></i>
      </button>
    </admin-page-header>

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
        <!-- Headline KPI tiles -->
        <div class="row row-cards g-2 g-md-3 mb-3">
          <div class="col-6 col-md-3">
            <div class="card stat-accent" style="--accent: #206bc4; --accent-soft: #e7f0fb">
              <div class="card-body">
                <div class="subheader">Revenue (this month)</div>
                <div class="h1 m-0">{{ inr(d.revenueThisMonth) }}</div>
                <div class="text-secondary small mt-1">{{ d.ordersThisMonth }} orders</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="card stat-accent" style="--accent: #1f5d3f; --accent-soft: #e4f2ea">
              <div class="card-body">
                <div class="subheader">Delivery success</div>
                <div class="h1 m-0">{{ d.deliverySuccessRate }}%</div>
                <div class="text-secondary small mt-1">{{ d.delivered }} delivered · {{ d.failed }} failed</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="card stat-accent" style="--accent: #f59f00; --accent-soft: #fdf1da">
              <div class="card-body">
                <div class="subheader">Lead conversion</div>
                <div class="h1 m-0">{{ d.leadConversionRate }}%</div>
                <div class="text-secondary small mt-1">{{ d.leadsWon }} won of {{ d.leadsTotal }}</div>
              </div>
            </div>
          </div>
          <div class="col-6 col-md-3">
            <div class="card stat-accent" style="--accent: #d63939; --accent-soft: #fbe7e7">
              <div class="card-body">
                <div class="subheader">COD outstanding</div>
                <div class="h1 m-0">{{ inr(d.codOutstanding) }}</div>
                <div class="text-secondary small mt-1">{{ d.memberCount }} in team</div>
              </div>
            </div>
          </div>
        </div>

        <!-- Headlines -->
        <div class="row g-2 g-md-3 mb-3">
          <div class="col-12 col-md-6">
            <div class="card h-100">
              <div class="card-body d-flex align-items-center gap-3">
                <span class="stat-icon" style="--accent: #1f5d3f; --accent-soft: #e4f2ea"><i class="ti ti-user-star"></i></span>
                <div>
                  <div class="subheader">Top performer (this month)</div>
                  <div class="fw-medium">{{ d.topPerformerName ?? '—' }}</div>
                </div>
              </div>
            </div>
          </div>
          <div class="col-12 col-md-6">
            <div class="card h-100">
              <div class="card-body d-flex align-items-center gap-3">
                <span class="stat-icon" style="--accent: #206bc4; --accent-soft: #e7f0fb"><i class="ti ti-trophy"></i></span>
                <div>
                  <div class="subheader">Best-converting source</div>
                  <div class="fw-medium">{{ d.topSource ? sourceLabel(d.topSource) : '—' }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Leaderboard -->
        <div class="card mb-3">
          <div class="card-header py-2"><span class="fw-medium">Salesperson leaderboard</span></div>
          <div class="table-responsive">
            <table class="table table-vcenter card-table">
              <thead>
                <tr>
                  <th>Salesperson</th>
                  <th class="text-end">Orders (mo)</th>
                  <th class="text-end">Revenue (mo)</th>
                  <th class="text-end">Delivery</th>
                  <th class="text-end d-none d-md-table-cell">COD out.</th>
                </tr>
              </thead>
              <tbody>
                @for (m of d.leaderboard; track m.id) {
                  <tr>
                    <td>
                      <div class="fw-medium">{{ m.fullName }}</div>
                      <div class="text-secondary small">{{ '@' + m.username }}</div>
                    </td>
                    <td class="text-end">{{ m.ordersThisMonth }}</td>
                    <td class="text-end">{{ inr(m.revenueThisMonth) }}</td>
                    <td class="text-end">{{ m.successRate }}%</td>
                    <td class="text-end d-none d-md-table-cell">{{ inr(m.codOutstanding) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>

        <!-- Lead-source conversion -->
        <div class="card">
          <div class="card-header py-2"><span class="fw-medium">Lead source conversion</span></div>
          @if (d.leadSources.length === 0) {
            <div class="card-body text-secondary">No leads captured by your team yet.</div>
          } @else {
            <div class="table-responsive">
              <table class="table table-vcenter card-table">
                <thead>
                  <tr>
                    <th>Source</th>
                    <th class="text-end">Leads</th>
                    <th class="text-end">Won</th>
                    <th class="text-end">Conversion</th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of d.leadSources; track s.source) {
                    <tr>
                      <td class="fw-medium">{{ sourceLabel(s.source) }}</td>
                      <td class="text-end">{{ s.leads }}</td>
                      <td class="text-end">{{ s.won }}</td>
                      <td class="text-end">
                        <span class="badge" [class.bg-green-lt]="s.conversionRate >= 50" [class.bg-yellow-lt]="s.conversionRate < 50">
                          {{ s.conversionRate }}%
                        </span>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      }
    }
  `,
})
export class TeamPerformanceComponent implements OnInit {
  private readonly service = inject(TeamPerformanceService);

  protected readonly data = signal<TeamPerformance | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.performance().subscribe({
      next: (res) => {
        this.data.set(res);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load team performance. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Friendly label for a lead-source enum name. */
  sourceLabel(source: string): string {
    return SOURCE_LABELS[source] ?? source;
  }

  /** Formats a decimal-string amount as ₹. */
  inr(amount: string | number | null | undefined): string {
    const n = Number(amount ?? 0);
    return `₹${n.toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }
}
