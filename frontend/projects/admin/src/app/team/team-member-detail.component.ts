import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import {
  DirectReportPerformance,
  TeamMemberPerformance,
  TeamPerformanceService,
} from './team-performance.service';

/**
 * Right-side detail drawer for an authorised Team Lead direct report. It shows
 * safe work-profile data plus lifetime, recent-period, lead, and order detail.
 */
@Component({
  selector: 'admin-team-member-detail',
  standalone: true,
  styleUrl: './team-member-detail.component.css',
  template: `
    <div class="tm-detail__backdrop" (click)="closed.emit()">
      <aside class="tm-detail" role="dialog" aria-modal="true" [attr.aria-label]="member.fullName + ' performance detail'" (click)="$event.stopPropagation()">
        <header class="tm-detail__header">
          <div>
            <div class="subheader">Salesperson performance</div>
            <h2 class="tm-detail__name">{{ member.fullName }}</h2>
            <div class="text-secondary small">{{ '@' + member.username }}</div>
          </div>
          <button type="button" class="btn btn-icon btn-ghost-secondary" (click)="closed.emit()" aria-label="Close performance detail">
            <i class="ti ti-x"></i>
          </button>
        </header>

        @if (loading()) {
          <div class="tm-detail__state"><span class="spinner-border spinner-border-sm me-2"></span>Loading salesperson details…</div>
        } @else if (error()) {
          <div class="tm-detail__state text-danger">
            {{ error() }}
            <button type="button" class="btn btn-sm btn-outline-danger ms-2" (click)="load()">Retry</button>
          </div>
        } @else if (detail(); as d) {
          <div class="tm-detail__body">
            <section class="tm-detail__profile">
              <div class="tm-detail__avatar">{{ initials(d.profile.fullName) }}</div>
              <div class="min-w-0">
                <div class="fw-bold">{{ d.profile.fullName }}</div>
                <div class="text-secondary small">{{ d.profile.mobile || d.profile.email || 'No contact details' }}</div>
              </div>
              <span class="badge ms-auto" [class.tone-green]="d.profile.active" [class.tone-red]="!d.profile.active">
                {{ d.profile.active ? 'Active' : 'Inactive' }}
              </span>
            </section>

            <div class="tm-detail__contact">
              <span><i class="ti ti-mail me-1"></i>{{ d.profile.email || 'Email not recorded' }}</span>
              <span><i class="ti ti-phone me-1"></i>{{ d.profile.mobile || 'Mobile not recorded' }}</span>
              <span><i class="ti ti-calendar-event me-1"></i>Joined {{ dateLabel(d.profile.joinedOn) }}</span>
              <span><i class="ti ti-shield-check me-1"></i>{{ d.profile.verificationStatus || 'Verification pending' }}</span>
            </div>

            <div class="tm-detail__section-title">Orders at a glance</div>
            <div class="tm-detail__grid">
              <div class="tm-detail__metric"><span>Lifetime orders</span><strong>{{ d.performance.summary.ordersTotal }}</strong><small>{{ money(d.performance.summary.revenueTotal) }}</small></div>
              <div class="tm-detail__metric"><span>Orders today</span><strong>{{ d.performance.summary.ordersToday }}</strong><small>{{ money(todayRevenue(d)) }}</small></div>
              <div class="tm-detail__metric"><span>Last 7 days</span><strong>{{ ordersFor(d, 7) }}</strong><small>{{ money(revenueFor(d, 7)) }}</small></div>
              <div class="tm-detail__metric"><span>Last month</span><strong>{{ lastMonthOrders(d) }}</strong><small>{{ money(lastMonthRevenue(d)) }}</small></div>
              <div class="tm-detail__metric"><span>This month</span><strong>{{ d.performance.summary.ordersThisMonth }}</strong><small>{{ money(d.performance.summary.revenueThisMonth) }}</small></div>
              <div class="tm-detail__metric"><span>Delivery success</span><strong>{{ d.performance.summary.successRate }}%</strong><small>{{ d.performance.summary.deliveredCount }} delivered · {{ d.performance.summary.failedCount }} failed</small></div>
            </div>

            <div class="tm-detail__section-title">Lead & collection health</div>
            <div class="tm-detail__grid tm-detail__grid--three">
              <div class="tm-detail__metric"><span>Lead conversion</span><strong>{{ d.performance.leads.conversionRate }}%</strong><small>{{ d.performance.leads.won }} won of {{ d.performance.leads.total }}</small></div>
              <div class="tm-detail__metric"><span>Follow-ups due</span><strong [class.text-danger]="d.performance.leads.dueFollowUps > 0">{{ d.performance.leads.dueFollowUps }}</strong><small>{{ d.performance.leads.active }} active leads</small></div>
              <div class="tm-detail__metric"><span>COD outstanding</span><strong>{{ money(d.performance.summary.codOutstanding) }}</strong><small>Across open orders</small></div>
            </div>

            <div class="tm-detail__section-title d-flex align-items-center justify-content-between">
              <span>Daily activity</span>
              <div class="btn-group btn-group-sm">
                @for (days of [7, 30, 60]; track days) {
                  <button type="button" class="btn" [class.btn-primary]="activityDays() === days" (click)="activityDays.set(days)">{{ days }}d</button>
                }
              </div>
            </div>
            <div class="tm-detail__activity">
              @for (point of visibleTrend(d); track point.date) {
                <div class="tm-detail__activity-row">
                  <span>{{ dateLabel(point.date) }}</span>
                  <strong>{{ point.orders }} order{{ point.orders === 1 ? '' : 's' }}</strong>
                  <span>{{ money(point.revenue) }}</span>
                </div>
              } @empty {
                <div class="text-secondary small">No order activity in this period.</div>
              }
            </div>

            <div class="tm-detail__section-title">Latest orders</div>
            <div class="tm-detail__orders">
              @for (order of d.performance.recentOrders; track order.orderCode) {
                <div class="tm-detail__order">
                  <div class="min-w-0">
                    <div class="fw-semibold text-truncate">{{ order.orderCode }} · {{ order.customerName }}</div>
                    <div class="text-secondary small">{{ dateTimeLabel(order.createdAt) }} · {{ statusLabel(order.orderStatus) }}</div>
                  </div>
                  <div class="text-end">
                    <div class="fw-semibold">{{ money(order.totalAmount) }}</div>
                    <div class="text-secondary small">{{ order.paymentStatus || '—' }}</div>
                  </div>
                </div>
              } @empty {
                <div class="text-secondary small">No orders have been created yet.</div>
              }
            </div>
          </div>
        }
      </aside>
    </div>
  `,
})
export class TeamMemberDetailComponent implements OnInit {
  @Input({ required: true }) member!: TeamMemberPerformance;
  @Output() readonly closed = new EventEmitter<void>();

  private readonly service = inject(TeamPerformanceService);
  protected readonly detail = signal<DirectReportPerformance | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly activityDays = signal(7);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.detail(this.member.id, 60).subscribe({
      next: (detail) => {
        this.detail.set(detail);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load this salesperson’s detail.');
        this.loading.set(false);
      },
    });
  }

  protected initials(name: string): string {
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
  }

  protected ordersFor(detail: DirectReportPerformance, days: number): number {
    return detail.performance.trend.slice(-days).reduce((total, point) => total + point.orders, 0);
  }

  protected revenueFor(detail: DirectReportPerformance, days: number): number {
    return detail.performance.trend.slice(-days).reduce((total, point) => total + Number(point.revenue || 0), 0);
  }

  protected todayRevenue(detail: DirectReportPerformance): number {
    const today = new Date().toISOString().slice(0, 10);
    return Number(detail.performance.trend.find((point) => point.date === today)?.revenue ?? 0);
  }

  protected lastMonthOrders(detail: DirectReportPerformance): number {
    return this.lastMonth(detail, (point) => point.orders);
  }

  protected lastMonthRevenue(detail: DirectReportPerformance): number {
    return this.lastMonth(detail, (point) => Number(point.revenue || 0));
  }

  private lastMonth(detail: DirectReportPerformance, value: (point: DirectReportPerformance['performance']['trend'][number]) => number): number {
    const now = new Date();
    const previous = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const yearMonth = `${previous.getFullYear()}-${String(previous.getMonth() + 1).padStart(2, '0')}`;
    return detail.performance.trend
      .filter((point) => point.date.startsWith(yearMonth))
      .reduce((total, point) => total + value(point), 0);
  }

  protected visibleTrend(detail: DirectReportPerformance) {
    return detail.performance.trend.slice(-this.activityDays()).reverse();
  }

  protected money(value: string | number | null | undefined): string {
    return `₹${Number(value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;
  }

  protected dateLabel(value: string | null | undefined): string {
    if (!value) {
      return 'Not recorded';
    }
    return new Date(`${value.slice(0, 10)}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  protected dateTimeLabel(value: string | null | undefined): string {
    if (!value) {
      return 'Date not recorded';
    }
    return new Date(value).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  }

  protected statusLabel(value: string | null | undefined): string {
    return (value || '—').toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  }
}
