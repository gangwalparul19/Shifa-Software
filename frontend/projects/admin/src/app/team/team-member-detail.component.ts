import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { AuthService, Role } from 'core';
import { saveBlob } from '../shared/blob-download.util';
import { todayIst } from '../shared/business-date.util';
import { formatDateLabel } from '../shared/date-label.util';
import { formatInr } from '../shared/inr.pipe';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import { PeriodSelection, THIS_MONTH, isUsableSelection } from '../shared/period-selection';
import { humanizeStatus } from '../shared/status-badge.component';
import { DialogDirective } from '../shared/dialog.directive';
import { ToastService } from '../shared/toast.service';
import {
  PerformanceExportFormat,
  SalespeopleService,
  SalespersonPeriodDetail,
} from '../salespeople/salespeople.service';
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
  imports: [DialogDirective, PeriodPickerComponent],
  styleUrl: './team-member-detail.component.css',
  template: `
    <div class="tm-detail__backdrop" (click)="closed.emit()">
      <aside class="tm-detail" adminDialog [attr.aria-label]="member.fullName + ' performance detail'" (dialogClose)="closed.emit()" (click)="$event.stopPropagation()">
        <header class="tm-detail__header">
          <div>
            <div class="subheader">Salesperson performance</div>
            <h2 class="tm-detail__name">{{ member.fullName }}</h2>
            @if (member.username) {
              <div class="text-secondary small">{{ '@' + member.username }}</div>
            }
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

            <div class="tm-detail__tabs" role="tablist">
              <button type="button" class="btn btn-sm" [class.btn-primary]="detailTab() === 'overview'" [class.btn-outline-secondary]="detailTab() !== 'overview'" (click)="detailTab.set('overview')">Overview</button>
              <button type="button" class="btn btn-sm" [class.btn-primary]="detailTab() === 'activity'" [class.btn-outline-secondary]="detailTab() !== 'activity'" (click)="detailTab.set('activity')">Activity</button>
              <button type="button" class="btn btn-sm" [class.btn-primary]="detailTab() === 'orders'" [class.btn-outline-secondary]="detailTab() !== 'orders'" (click)="detailTab.set('orders')">Orders</button>
            </div>

            @if (detailTab() === 'overview') {
            @if (isAdmin) {
              <div class="tm-detail__section-title d-flex flex-wrap align-items-center justify-content-between gap-2">
                <span>Sales in a period</span>
                <span class="btn-list">
                  <button type="button" class="btn btn-sm btn-outline-success" (click)="exportPeriod('xlsx')"
                    [disabled]="exporting() !== null" title="Download this person's report for the chosen period as Excel">
                    @if (exporting() === 'xlsx') { <span class="spinner-border spinner-border-sm me-1"></span> }
                    <i class="ti ti-file-spreadsheet me-1" aria-hidden="true"></i> Excel
                  </button>
                  <button type="button" class="btn btn-sm btn-outline-danger" (click)="exportPeriod('pdf')"
                    [disabled]="exporting() !== null" title="Download this person's report for the chosen period as PDF">
                    @if (exporting() === 'pdf') { <span class="spinner-border spinner-border-sm me-1"></span> }
                    <i class="ti ti-file-type-pdf me-1" aria-hidden="true"></i> PDF
                  </button>
                </span>
              </div>
              <admin-period-picker
                [value]="selection()"
                ariaLabel="Period for this salesperson"
                (changed)="choosePeriod($event)"
              />
              <div class="mt-2">
                @if (periodLoading()) {
                  <div class="text-secondary small"><span class="spinner-border spinner-border-sm me-2"></span>Loading…</div>
                } @else if (periodError()) {
                  <div class="text-danger small">
                    {{ periodError() }}
                    <button type="button" class="btn btn-sm btn-outline-danger ms-2" (click)="loadPeriod()">Retry</button>
                  </div>
                } @else if (periodDetail(); as p) {
                  <div class="text-secondary small mb-1">{{ p.label }}</div>
                  <div class="tm-detail__grid">
                    <div class="tm-detail__metric"><span>Orders</span><strong>{{ p.orders }}</strong><small>placed in this period</small></div>
                    <div class="tm-detail__metric"><span>Sales</span><strong>{{ money(p.sales) }}</strong><small>average order {{ money(p.averageOrderValue) }}</small></div>
                    <div class="tm-detail__metric"><span>Delivered</span><strong>{{ p.delivered }}</strong><small>of these orders so far</small></div>
                    <div class="tm-detail__metric"><span>Failed or returned</span><strong>{{ p.failed }}</strong><small>of these orders so far</small></div>
                  </div>
                }
              </div>
            }
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
              <div class="tm-detail__metric"><span>Outstanding on delivery</span><strong>{{ money(d.performance.summary.codOutstanding) }}</strong><small>Across open orders</small></div>
            </div>
            }

            @if (detailTab() === 'activity') {
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
            }

            @if (detailTab() === 'orders') {
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
            }
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
  private readonly salespeople = inject(SalespeopleService);
  private readonly toasts = inject(ToastService);
  protected readonly detail = signal<DirectReportPerformance | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly activityDays = signal(7);
  protected readonly detailTab = signal<'overview' | 'activity' | 'orders'>('overview');

  /**
   * Only an admin gets the "Sales in a period" block (any period, plus Excel / PDF): it uses the
   * admin Salespeople endpoints. A team lead keeps the fixed buckets below.
   */
  protected readonly isAdmin = inject(AuthService).hasAnyRole(Role.ADMIN);
  protected readonly selection = signal<PeriodSelection>(THIS_MONTH);
  protected readonly periodDetail = signal<SalespersonPeriodDetail | null>(null);
  protected readonly periodLoading = signal(false);
  protected readonly periodError = signal<string | null>(null);
  protected readonly exporting = signal<PerformanceExportFormat | null>(null);
  /** Numbers each period load so a slow answer for an earlier choice never overwrites a newer one. */
  private periodRequest = 0;

  ngOnInit(): void {
    this.load();
    if (this.isAdmin) {
      this.loadPeriod();
    }
  }

  /** The admin picked another period. */
  protected choosePeriod(selection: PeriodSelection): void {
    this.selection.set(selection);
    this.loadPeriod();
  }

  protected loadPeriod(): void {
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      return;
    }
    const id = ++this.periodRequest;
    this.periodLoading.set(true);
    this.periodError.set(null);
    this.salespeople.performance(this.member.id, undefined, selection).subscribe({
      next: (d) => {
        if (id !== this.periodRequest) {
          return;
        }
        this.periodDetail.set(d.period ?? null);
        this.periodLoading.set(false);
      },
      error: () => {
        if (id !== this.periodRequest) {
          return;
        }
        // Never leave the old figures on screen under a new period's label.
        this.periodDetail.set(null);
        this.periodError.set('Could not load the figures for this period.');
        this.periodLoading.set(false);
      },
    });
  }

  /** Downloads this person's full report for the chosen period. */
  protected exportPeriod(format: PerformanceExportFormat): void {
    const selection = this.selection();
    if (this.exporting()) {
      return;
    }
    if (!isUsableSelection(selection)) {
      this.toasts.error('Choose a valid start and end date first.');
      return;
    }
    this.exporting.set(format);
    this.salespeople.exportPerformance(format, selection, this.member.id).subscribe({
      next: (result) => {
        saveBlob(result.blob, result.filename, `performance.${format}`);
        this.exporting.set(null);
        this.toasts.success(`Performance ${format.toUpperCase()} downloaded.`);
      },
      error: () => {
        this.exporting.set(null);
        this.toasts.error(`Could not download the performance ${format.toUpperCase()}.`);
      },
    });
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
    const today = todayIst();
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
    return formatInr(value, 0);
  }

  protected dateLabel(value: string | null | undefined): string {
    // Only the calendar-date part matters here (joined-on / daily activity dates).
    return formatDateLabel(value ? value.slice(0, 10) : value, { empty: 'Not recorded', day: 'numeric' });
  }

  protected dateTimeLabel(value: string | null | undefined): string {
    if (!value) {
      return 'Date not recorded';
    }
    return new Date(value).toLocaleString('en-IN', {
      day: 'numeric',
      month: 'short',
      hour: 'numeric',
      minute: '2-digit',
      timeZone: 'Asia/Kolkata',
    });
  }

  protected statusLabel(value: string | null | undefined): string {
    return value ? humanizeStatus(value) : '—';
  }
}
