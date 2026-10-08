import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexFill,
  ApexGrid,
  ApexLegend,
  ApexPlotOptions,
  ApexStroke,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { CountUpDirective } from '../shared/count-up.directive';
import { ChannelLogoComponent } from '../shared/channel-logo.component';
import { AdminEventsService } from './admin-events.service';
import { DashboardService } from './dashboard.service';
import { ActivityCards, InsightsSummary, MetricsPeriod, PeriodOption, SalesBucket } from './dashboard.model';
import {
  ChannelDashboardData,
  ChannelStageCount,
  ChannelTotals,
  DashboardChannel,
  RankRow,
} from './channel-dashboard.model';

/** ApexCharts options for the revenue-trend chart. */
interface TrendChartOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  fill: ApexFill;
  plotOptions: ApexPlotOptions;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis;
  legend: ApexLegend;
  tooltip: ApexTooltip;
  grid: ApexGrid;
}

/** ApexCharts options for the payment-mix donut. */
interface MixChartOptions {
  series: number[];
  labels: string[];
  colors: string[];
  chart: ApexChart;
  legend: ApexLegend;
  dataLabels: ApexDataLabels;
  plotOptions: ApexPlotOptions;
  stroke: ApexStroke;
  tooltip: ApexTooltip;
}

/** Visual style for one lifecycle stage in the pipeline. */
const STAGE_STYLE: Record<string, { color: string; icon: string }> = {
  PENDING_APPROVAL: { color: '#f59f00', icon: 'ti-clock' },
  PROCESSING: { color: '#0ca678', icon: 'ti-box' },
  SHIPPED: { color: '#206bc4', icon: 'ti-truck' },
  DELIVERED: { color: '#2fb344', icon: 'ti-circle-check' },
  FAILED_RETURNED: { color: '#d63939', icon: 'ti-arrow-back-up' },
  CANCELLED: { color: '#868e96', icon: 'ti-ban' },
  REJECTED: { color: '#ae3ec9', icon: 'ti-circle-x' },
};

/** Channel colours used consistently across tiles, charts and chips. */
export const CHANNEL_COLOR: Record<DashboardChannel, string> = {
  ALL: '#1f5d3f',
  PORTAL: '#206bc4',
  SHOPIFY: '#5e8e3e',
};

/**
 * The channel-aware ADMIN dashboard. One All / Portal / Shopify switch scopes
 * every section (the comparison strip always shows all three). Built from
 * {@code GET /api/admin/dashboard/channel}; the Activity and Notifications panels
 * reuse the live SSE feed. Every clickable tile drills into the Orders page with
 * the same channel, stage and date window.
 */
@Component({
  selector: 'admin-channel-overview',
  standalone: true,
  imports: [CountUpDirective, NgApexchartsModule, NgTemplateOutlet, RouterLink, ChannelLogoComponent],
  templateUrl: './admin-channel-overview.component.html',
  styleUrl: './admin-channel-overview.component.css',
})
export class AdminChannelOverviewComponent implements OnInit {
  private readonly service = inject(DashboardService);
  private readonly router = inject(Router);
  protected readonly events = inject(AdminEventsService);

  /** Statistical-insights counts from the role summary (loaded by the parent). */
  readonly insights = input<InsightsSummary | null>(null);

  private static readonly CHANNEL_KEY = 'shifa.dashboardChannel.v1';
  private static readonly SECTIONS_KEY = 'shifa.dashboardHiddenSections.v2';

  protected readonly CHANNEL_COLOR = CHANNEL_COLOR;
  protected readonly channels: { value: DashboardChannel; label: string; icon: string; color: string }[] = [
    { value: 'ALL', label: 'All orders', icon: 'ti-layers-intersect', color: CHANNEL_COLOR.ALL },
    { value: 'PORTAL', label: 'Portal', icon: 'ti-building-store', color: CHANNEL_COLOR.PORTAL },
    { value: 'SHOPIFY', label: 'Shopify', icon: 'ti-brand-shopify', color: CHANNEL_COLOR.SHOPIFY },
  ];

  /** Colour dot for the active channel in the scope line. */
  protected readonly channelColor = computed(() => CHANNEL_COLOR[this.channel()]);

  protected readonly periodOptions: PeriodOption[] = [
    { value: 'TODAY', label: 'Today' },
    { value: 'YESTERDAY', label: 'Yesterday' },
    { value: 'LAST_7_DAYS', label: 'Last 7 days' },
    { value: 'LAST_30_DAYS', label: 'Last 30 days' },
    { value: 'THIS_MONTH', label: 'This month' },
    { value: 'LAST_MONTH', label: 'Last month' },
    { value: 'QUARTERLY', label: 'This quarter' },
    { value: 'YEARLY', label: 'This year' },
    { value: 'CUSTOM', label: 'Custom range' },
  ];

  protected readonly buckets: { value: SalesBucket; label: string }[] = [
    { value: 'DAY', label: 'Day' },
    { value: 'WEEK', label: 'Week' },
    { value: 'MONTH', label: 'Month' },
  ];

  /** Sections the admin can hide (Customize). */
  protected readonly sections: { key: string; label: string }[] = [
    { key: 'split', label: 'Channel comparison' },
    { key: 'kpis', label: 'Headline KPIs' },
    { key: 'trend', label: 'Revenue trend & pipeline' },
    { key: 'queues', label: 'Work queues' },
    { key: 'payments', label: 'Payments & cash' },
    { key: 'performance', label: 'Top performers' },
    { key: 'activity', label: 'Activity & notifications' },
  ];

  protected readonly channel = signal<DashboardChannel>(this.loadChannel());
  protected readonly period = signal<MetricsPeriod>('LAST_30_DAYS');
  protected readonly bucket = signal<SalesBucket | null>(null);
  protected readonly customFrom = signal('');
  protected readonly customTo = signal('');

  protected readonly data = signal<ChannelDashboardData | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly hidden = signal<Set<string>>(this.loadHidden());
  protected readonly customizeOpen = signal(false);

  private readonly restActivity = signal<ActivityCards | null>(null);
  protected readonly activity = computed(() => this.events.activity() ?? this.restActivity());

  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  protected readonly channelLabel = computed(
    () => this.channels.find((c) => c.value === this.channel())?.label ?? '',
  );

  protected readonly periodLabel = computed(() => {
    const d = this.data();
    const label = this.periodOptions.find((o) => o.value === this.period())?.label ?? '';
    if (d?.from && d?.to) {
      return `${label} · ${this.shortDate(d.from)} – ${this.shortDate(d.to)}`;
    }
    return label;
  });

  /** The three comparison cards (All / Portal / Shopify), typed for the template. */
  protected readonly splitCards = computed(() => {
    const s = this.data()?.split;
    if (!s) {
      return [];
    }
    const rows: { key: DashboardChannel; label: string; icon: string; t: ChannelTotals }[] = [
      { key: 'ALL', label: 'All orders', icon: 'ti-layers-intersect', t: s.all },
      { key: 'PORTAL', label: 'Portal', icon: 'ti-building-store', t: s.portal },
      { key: 'SHOPIFY', label: 'Shopify', icon: 'ti-brand-shopify', t: s.shopify },
    ];
    return rows.map((r) => ({ ...r, color: CHANNEL_COLOR[r.key] }));
  });

  /** Clicking a comparison card: "All" drills into orders, a channel switches the dashboard to it. */
  pickSplit(key: DashboardChannel): void {
    if (key === 'ALL') {
      this.drill();
    } else {
      this.selectChannel(key);
    }
  }

  /** Pipeline stages with a width relative to the biggest stage, for the bar list. */
  protected readonly pipelineRows = computed(() => {
    const rows = this.data()?.pipeline ?? [];
    const max = Math.max(1, ...rows.map((r) => r.count));
    return rows.map((r) => ({
      ...r,
      color: STAGE_STYLE[r.group]?.color ?? '#868e96',
      icon: STAGE_STYLE[r.group]?.icon ?? 'ti-circle',
      widthPct: Math.max(r.count > 0 ? 3 : 0, Math.round((r.count / max) * 100)),
    }));
  });

  protected readonly insightDanger = computed(() => this.insights()?.countsBySeverity?.['DANGER'] ?? 0);
  protected readonly insightWarning = computed(() => this.insights()?.countsBySeverity?.['WARNING'] ?? 0);
  protected readonly insightTotal = computed(() => {
    const c = this.insights()?.countsBySeverity ?? {};
    return (c['DANGER'] ?? 0) + (c['WARNING'] ?? 0) + (c['INFO'] ?? 0);
  });

  /**
   * Revenue trend. In "All" mode: Portal and Shopify as stacked columns plus the
   * previous period's total as a line. In a single-channel mode: that channel's
   * revenue as an area against the previous period (dashed).
   */
  protected readonly trendChart = computed<TrendChartOptions | null>(() => {
    const d = this.data();
    if (!d || d.trend.length === 0) {
      return null;
    }
    const categories = d.trend.map((p) => this.shortLabel(p.label));
    const hasPrev = d.trend.some((p) => p.previousTotal > 0);
    const all = d.channel === 'ALL';
    const series: ApexAxisChartSeries = all
      ? [
          { name: 'Portal', type: 'column', data: d.trend.map((p) => Math.round(p.portal)) },
          { name: 'Shopify', type: 'column', data: d.trend.map((p) => Math.round(p.shopify)) },
        ]
      : [{ name: this.channelLabel(), type: 'area', data: d.trend.map((p) => Math.round(p.total)) }];
    if (hasPrev) {
      series.push({ name: 'Previous period', type: 'line', data: d.trend.map((p) => Math.round(p.previousTotal)) });
    }
    const colors = all
      ? [CHANNEL_COLOR.PORTAL, CHANNEL_COLOR.SHOPIFY, '#c9a227']
      : [CHANNEL_COLOR[d.channel], '#c9a227'];
    return {
      series,
      chart: {
        type: 'line',
        height: 300,
        stacked: all,
        fontFamily: 'inherit',
        toolbar: { show: false },
        zoom: { enabled: false },
        parentHeightOffset: 0,
        animations: { enabled: !this.reducedMotion },
      },
      colors,
      dataLabels: { enabled: false },
      stroke: {
        curve: 'smooth',
        width: series.map((s) => (s.type === 'column' ? 0 : s.name === 'Previous period' ? 2 : 3)),
        dashArray: series.map((s) => (s.name === 'Previous period' ? 5 : 0)),
      },
      fill: {
        type: series.map((s) => (s.type === 'area' ? 'gradient' : 'solid')),
        opacity: series.map((s) => (s.type === 'area' ? 0.35 : 1)),
        gradient: { shadeIntensity: 1, opacityFrom: 0.35, opacityTo: 0.05, stops: [0, 90, 100] },
      },
      plotOptions: { bar: { columnWidth: '60%', borderRadius: 3 } },
      xaxis: {
        categories,
        labels: { rotate: -45, hideOverlappingLabels: true, style: { colors: '#6b7c74' } },
        axisBorder: { show: false },
        axisTicks: { show: false },
        tooltip: { enabled: false },
      },
      yaxis: { labels: { formatter: (v: number) => this.compact(v), style: { colors: '#6b7c74' } } },
      legend: { position: 'top', horizontalAlign: 'right', fontFamily: 'inherit' },
      tooltip: { theme: 'light', shared: true, intersect: false, y: { formatter: (v: number) => this.inr(v) } },
      grid: { borderColor: 'rgba(15,51,36,0.08)', strokeDashArray: 4, padding: { left: 8, right: 8 } },
    };
  });

  /** Payment mix donut (order counts per payment type). */
  protected readonly mixChart = computed<MixChartOptions | null>(() => {
    const mix = this.data()?.payments.mix ?? [];
    const total = mix.reduce((s, m) => s + m.orders, 0);
    if (total === 0) {
      return null;
    }
    return {
      series: mix.map((m) => m.orders),
      labels: mix.map((m) => m.label),
      colors: ['#2fb344', '#f59f00', '#206bc4'],
      chart: { type: 'donut', height: 240, fontFamily: 'inherit', animations: { enabled: !this.reducedMotion } },
      legend: { position: 'bottom', fontFamily: 'inherit' },
      dataLabels: { enabled: true, formatter: (v: number) => `${Math.round(v)}%` },
      plotOptions: {
        pie: {
          donut: {
            size: '66%',
            labels: { show: true, total: { show: true, label: 'Orders', formatter: () => String(total) } },
          },
        },
      },
      stroke: { width: 2, colors: ['#fff'] },
      tooltip: { theme: 'light', y: { formatter: (v: number) => `${v} orders` } },
    };
  });

  ngOnInit(): void {
    this.load();
    this.service.activity().subscribe({ next: (a) => this.restActivity.set(a), error: () => undefined });
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    const period = this.period();
    if (period === 'CUSTOM' && !this.canApplyCustom()) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.service
      .channelDashboard(
        this.channel(),
        period,
        this.bucket(),
        period === 'CUSTOM' ? this.customFrom() : null,
        period === 'CUSTOM' ? this.customTo() : null,
      )
      .subscribe({
        next: (d) => {
          this.data.set(d);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Could not load the dashboard. Please try again.');
          this.loading.set(false);
        },
      });
  }

  selectChannel(value: DashboardChannel): void {
    if (this.channel() === value) {
      return;
    }
    this.channel.set(value);
    try {
      localStorage.setItem(AdminChannelOverviewComponent.CHANNEL_KEY, value);
    } catch {
      /* storage unavailable — non-fatal */
    }
    this.load();
  }

  selectPeriod(value: MetricsPeriod): void {
    this.period.set(value);
    this.bucket.set(null);
    if (value !== 'CUSTOM') {
      this.load();
    }
  }

  selectBucket(value: SalesBucket): void {
    this.bucket.set(value);
    this.load();
  }

  canApplyCustom(): boolean {
    return !!this.customFrom() && !!this.customTo() && this.customFrom() <= this.customTo();
  }

  // --- Sections -----------------------------------------------------------

  visible(key: string): boolean {
    return !this.hidden().has(key);
  }

  toggleSection(key: string): void {
    const next = new Set(this.hidden());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.hidden.set(next);
    try {
      localStorage.setItem(AdminChannelOverviewComponent.SECTIONS_KEY, JSON.stringify([...next]));
    } catch {
      /* non-fatal */
    }
  }

  toggleCustomize(): void {
    this.customizeOpen.update((v) => !v);
  }

  // --- Drill-down ---------------------------------------------------------

  /** Opens the Orders page filtered by channel, optional stage, and the dashboard window. */
  drill(statusGroup?: string, channelOverride?: DashboardChannel): void {
    const d = this.data();
    const channel = channelOverride ?? this.channel();
    const queryParams: Record<string, string> = {};
    if (statusGroup) {
      queryParams['statusGroup'] = statusGroup;
    }
    if (channel === 'SHOPIFY') {
      queryParams['source'] = 'SHOPIFY';
    } else if (channel === 'PORTAL') {
      queryParams['source'] = 'SALESPERSON';
    }
    if (d?.from) {
      queryParams['from'] = d.from;
    }
    if (d?.to) {
      queryParams['to'] = d.to;
    }
    void this.router.navigate(['/orders'], { queryParams });
  }

  /** Opens the Orders page for a live queue (no date window — queues are not period-limited). */
  drillQueue(statusGroup: string, channel: DashboardChannel): void {
    const queryParams: Record<string, string> = { statusGroup };
    queryParams['source'] = channel === 'SHOPIFY' ? 'SHOPIFY' : 'SALESPERSON';
    void this.router.navigate(['/orders'], { queryParams });
  }

  go(path: string): void {
    void this.router.navigate([path]);
  }

  clearNotifications(): void {
    this.events.clearNotifications();
  }

  // --- Formatting ---------------------------------------------------------

  inr(value: number | null | undefined): string {
    const n = typeof value === 'number' ? value : 0;
    return `₹${Math.round(n).toLocaleString('en-IN')}`;
  }

  /** "+12.5%" / "−4.0%" / "—" for a nullable % change. */
  changeText(pct: number | null | undefined): string {
    if (pct === null || pct === undefined) {
      return '—';
    }
    const sign = pct > 0 ? '+' : pct < 0 ? '−' : '';
    return `${sign}${Math.abs(pct).toFixed(1)}%`;
  }

  changeDir(pct: number | null | undefined): 'up' | 'down' | 'flat' {
    if (pct === null || pct === undefined || pct === 0) {
      return 'flat';
    }
    return pct > 0 ? 'up' : 'down';
  }

  /** Revenue share of a channel, for the comparison bar. */
  share(t: ChannelTotals | undefined): number {
    return t ? Math.min(100, Math.max(0, Number(t.revenueSharePct) || 0)) : 0;
  }

  /** Width of a leaderboard bar relative to the list leader. */
  barPct(row: RankRow, rows: RankRow[]): number {
    const max = Math.max(1, ...rows.map((r) => r.revenue));
    return Math.max(4, Math.round((row.revenue / max) * 100));
  }

  trackStage(_: number, s: ChannelStageCount): string {
    return s.group;
  }

  private compact(value: number): string {
    if (value >= 1_00_00_000) return `${(value / 1_00_00_000).toFixed(1)}Cr`;
    if (value >= 1_00_000) return `${(value / 1_00_000).toFixed(1)}L`;
    if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
    return `${Math.round(value)}`;
  }

  private shortLabel(label: string): string {
    const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(label);
    if (iso) return `${iso[3]}/${iso[2]}`;
    const wk = /^Wk\s+(\d{4})-(\d{2})-(\d{2})$/.exec(label);
    if (wk) return `Wk ${wk[3]}/${wk[2]}`;
    const ym = /^(\d{4})-(\d{2})$/.exec(label);
    if (ym) {
      const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
      return `${months[Number(ym[2]) - 1]} ${ym[1].slice(2)}`;
    }
    return label;
  }

  private shortDate(iso: string): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    if (!m) return iso;
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${Number(m[3])} ${months[Number(m[2]) - 1]}`;
  }

  private loadChannel(): DashboardChannel {
    try {
      const v = localStorage.getItem(AdminChannelOverviewComponent.CHANNEL_KEY);
      return v === 'PORTAL' || v === 'SHOPIFY' || v === 'ALL' ? v : 'ALL';
    } catch {
      return 'ALL';
    }
  }

  private loadHidden(): Set<string> {
    try {
      const raw = localStorage.getItem(AdminChannelOverviewComponent.SECTIONS_KEY);
      return raw ? new Set<string>(JSON.parse(raw) as string[]) : new Set<string>();
    } catch {
      return new Set<string>();
    }
  }
}
