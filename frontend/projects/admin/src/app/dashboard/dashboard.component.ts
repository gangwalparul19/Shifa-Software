import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService, OrderStatus, Role } from 'core';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexFill,
  ApexGrid,
  ApexLegend,
  ApexMarkers,
  ApexPlotOptions,
  ApexResponsive,
  ApexStroke,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { AdminEventsService } from './admin-events.service';
import { CountUpDirective } from '../shared/count-up.directive';
import { PageHeaderComponent } from '../shared/page-header.component';
import { DashboardService } from './dashboard.service';
import { MyDay, MyDayService, ReorderDueCustomer, WinBackCustomer } from './my-day.service';
import { TeamPerformance, TeamPerformanceService } from '../team/team-performance.service';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { ORDER_STATUS_GROUPS } from '../orders/order-status-groups';
import {
  ActivityCards,
  DashboardMetrics,
  LiveStats,
  MetricsPeriod,
  PeriodOption,
  RoleDashboardSummary,
  SalesBucket,
} from './dashboard.model';

/** A single labelled count derived from a role-summary status map. */
interface StatusCount {
  key: string;
  label: string;
  value: number;
}

/**
 * A lifecycle-stage-group count for the salesperson/team-lead "By status"
 * tiles: the many raw statuses folded into the 9 business-facing groups the
 * Orders page uses, each with a colour accent + icon so the section reads as a
 * clean, scannable KPI row instead of a flat monochrome list.
 */
interface StageGroupCount {
  key: string;
  label: string;
  value: number;
  accent: string;
  accentSoft: string;
  icon: string;
}

/** Colour accent + icon for each of the 6 QuikShip-aligned lifecycle stage groups. */
const STAGE_GROUP_STYLE: Record<string, { accent: string; accentSoft: string; icon: string }> = {
  PENDING_APPROVAL: { accent: '#f59f00', accentSoft: '#fdf1da', icon: 'ti ti-clock' },
  PROCESSING: { accent: '#0ca678', accentSoft: '#e3f7f0', icon: 'ti ti-box' },
  SHIPPED: { accent: '#206bc4', accentSoft: '#e7f0fb', icon: 'ti ti-truck' },
  DELIVERED: { accent: '#2fb344', accentSoft: '#e5f6e8', icon: 'ti ti-circle-check' },
  FAILED_RETURNED: { accent: '#d63939', accentSoft: '#fbe7e7', icon: 'ti ti-alert-triangle' },
  CANCELLED: { accent: '#868e96', accentSoft: '#f1f3f5', icon: 'ti ti-ban' },
};

/** A laid-out bar + comparison-point for the hand-rolled SVG sales chart. */
interface ChartBar {
  x: number;
  y: number;
  w: number;
  h: number;
  label: string;
  sales: number;
  previousSales: number;
}

/** The fully resolved chart geometry consumed by the SVG template. */
interface ChartModel {
  width: number;
  height: number;
  bars: ChartBar[];
  previousLine: string;
  previousDots: { x: number; y: number }[];
  gridLines: { y: number; label: string }[];
  axisLabels: { x: number; label: string }[];
}

/** ApexCharts option bundle for the revenue-trend area chart (A1). */
interface RevenueChartOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  fill: ApexFill;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis;
  legend: ApexLegend;
  tooltip: ApexTooltip;
  grid: ApexGrid;
  markers: ApexMarkers;
}

/** ApexCharts option bundle for the orders-by-status donut (A1). */
interface StatusChartOptions {
  series: number[];
  chart: ApexChart;
  labels: string[];
  colors: string[];
  legend: ApexLegend;
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  plotOptions: ApexPlotOptions;
  tooltip: ApexTooltip;
  responsive: ApexResponsive[];
}

/** One clickable donut segment mapped to an Orders drill-down status (A2). */
interface StatusSegment {
  label: string;
  value: number;
  color: string;
  status: OrderStatus;
}

/**
 * The Shopify-style admin dashboard (Req 19.1&ndash;19.7, 11.2, 13.3, 17.4).
 *
 * <p>Presents metric cards, a time-period filter, a sales graph with a
 * previous-period comparison line and % change, real-time live stats, activity
 * cards, and top performers &mdash; all recalculated for the selected period
 * (Req 19.3). It subscribes to the admin SSE stream via {@link AdminEventsService}
 * so packed-order, status-change, claim, and failure alerts appear live in a
 * notification feed, and the live-stats / activity numbers refresh in real time
 * from the periodic SSE pushes (falling back to the REST snapshot until the
 * first push arrives).
 */
@Component({
  selector: 'admin-dashboard',
  imports: [CountUpDirective, PageHeaderComponent, NgApexchartsModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly service = inject(DashboardService);
  private readonly myDayService = inject(MyDayService);
  private readonly teamPerformanceService = inject(TeamPerformanceService);
  private readonly router = inject(Router);
  protected readonly events = inject(AdminEventsService);
  protected readonly auth = inject(AuthService);

  /** Expose the status enum to the template for card drill-downs. */
  protected readonly OrderStatus = OrderStatus;

  /** The signed-in user's role, driving which card set the dashboard renders (Req 3.1). */
  protected readonly role = computed<Role | null>(() => this.auth.session()?.role ?? null);

  /** Whether the current user is an ADMIN (gates the rich metrics dashboard + SSE). */
  protected readonly isAdmin = computed(() => this.role() === Role.ADMIN);

  /**
   * Whether the current user is a TEAM_LEAD. The backend returns their
   * team-aggregated order summary in the same shape as a salesperson's, so the
   * salesperson section renders it; this flag hides the salesperson-only actions
   * (New Order / My Leads) a team lead can't perform and relabels the section.
   */
  protected readonly isTeamLead = computed(() => this.role() === Role.TEAM_LEAD);

  /** A true salesperson (not a team lead) — gets the "My Day" + win-back widgets. */
  protected readonly isSalesperson = computed(() => this.role() === Role.SALESPERSON);

  // --- My Day + Win-back + Reorder-due (salesperson self-service) ---------
  protected readonly myDay = signal<MyDay | null>(null);
  protected readonly winBack = signal<WinBackCustomer[]>([]);
  protected readonly reorderDue = signal<ReorderDueCustomer[]>([]);
  protected readonly teamPerformance = signal<TeamPerformance | null>(null);

  /** Loads the salesperson's My Day snapshot + win-back + reorder-due (non-fatal). */
  private loadMyDay(): void {
    this.myDayService.myDay().subscribe({
      next: (d) => this.myDay.set(d),
      error: () => this.myDay.set(null),
    });
    this.myDayService.winBack().subscribe({
      next: (rows) => this.winBack.set(rows.slice(0, 6)),
      error: () => this.winBack.set([]),
    });
    this.myDayService.reorderDue().subscribe({
      next: (rows) => this.reorderDue.set(rows.slice(0, 6)),
      error: () => this.reorderDue.set([]),
    });
  }

  /** Loads the Team Lead's server-scoped direct-report KPI snapshot. */
  private loadTeamPerformance(): void {
    this.teamPerformanceService.performance().subscribe({
      next: (performance) => this.teamPerformance.set(performance),
      error: () => this.teamPerformance.set(null),
    });
  }

  /** Opens WhatsApp for a reorder-due customer with a friendly nudge. */
  waReorder(c: ReorderDueCustomer): void {
    openWhatsApp(c.mobile, whatsAppMessage('followup', { customerName: c.customerName }));
  }

  /** ₹ formatter for the My Day / win-back figures (no decimals for compactness). */
  money(value: string | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
  }

  /** Opens WhatsApp for a lapsed customer with a friendly win-back follow-up. */
  waCustomer(c: WinBackCustomer): void {
    openWhatsApp(c.mobile, whatsAppMessage('followup', { customerName: c.customerName }));
  }

  // --- Configurable dashboard sections (FEATURE-ROADMAP §6.4) --------------
  private static readonly SECTIONS_KEY = 'shifa.dashboardHiddenSections.v1';
  /** The admin dashboard sections a user can show/hide, in display order. */
  protected readonly adminSections: { key: string; label: string }[] = [
    { key: 'fulfilment', label: 'Fulfilment queues' },
    { key: 'live', label: 'Today (live)' },
    { key: 'orders', label: 'Orders KPIs' },
    { key: 'exceptions', label: 'Exceptions KPIs' },
    { key: 'finance', label: 'Finance KPIs' },
  ];
  /** Section keys the user has hidden (persisted locally). */
  protected readonly hiddenSections = signal<Set<string>>(this.loadHiddenSections());
  /** Whether the "customize" popover is open. */
  protected readonly customizeOpen = signal(false);

  /** Whether a dashboard section is currently shown. */
  sectionVisible(key: string): boolean {
    return !this.hiddenSections().has(key);
  }

  /** Toggles a dashboard section's visibility and persists the choice locally. */
  toggleSection(key: string): void {
    const next = new Set(this.hiddenSections());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.hiddenSections.set(next);
    try {
      localStorage.setItem(DashboardComponent.SECTIONS_KEY, JSON.stringify([...next]));
    } catch {
      /* storage unavailable — non-fatal */
    }
  }

  toggleCustomize(): void {
    this.customizeOpen.update((open) => !open);
  }

  private loadHiddenSections(): Set<string> {
    try {
      const raw = localStorage.getItem(DashboardComponent.SECTIONS_KEY);
      return raw ? new Set<string>(JSON.parse(raw) as string[]) : new Set<string>();
    } catch {
      return new Set<string>();
    }
  }

  // --- Role-shaped summary (all roles, Req 3.1–3.6) -----------------------
  protected readonly summary = signal<RoleDashboardSummary | null>(null);
  protected readonly summaryLoading = signal(true);
  protected readonly summaryError = signal<string | null>(null);

  /**
   * The salesperson/team-lead orders folded into the 9 business-facing
   * lifecycle stage groups (same partition the Orders page uses), each with a
   * colour accent + icon. Only non-empty groups are shown, in lifecycle order,
   * so the "By status" section reads as a clean KPI row rather than a long,
   * flat list of every raw status.
   */
  protected readonly salespersonStageGroups = computed<StageGroupCount[]>(() => {
    const map = this.summary()?.salesperson?.ordersByStatus;
    if (!map) {
      return [];
    }
    const result: StageGroupCount[] = [];
    for (const group of ORDER_STATUS_GROUPS) {
      let value = 0;
      for (const status of group.statuses) {
        value += map[String(status)] ?? 0;
      }
      if (value <= 0) {
        continue;
      }
      const style = STAGE_GROUP_STYLE[group.key];
      result.push({
        key: group.key,
        label: group.label,
        value,
        accent: style.accent,
        accentSoft: style.accentSoft,
        icon: style.icon,
      });
    }
    return result;
  });

  /** Total orders in scope (sum across all stage groups) for the header count. */
  protected readonly salespersonOrderTotal = computed<number>(() =>
    this.salespersonStageGroups().reduce((sum, g) => sum + g.value, 0),
  );

  /** The admin per-active-stage counts, as labelled counts. */
  protected readonly adminActiveStages = computed<StatusCount[]>(() =>
    this.toStatusCounts(this.summary()?.admin?.perActiveStage),
  );

  /** The admin exception-state counts, as labelled counts. */
  protected readonly adminExceptions = computed<StatusCount[]>(() =>
    this.toStatusCounts(this.summary()?.admin?.exceptionStates),
  );

  /**
   * The salesperson's lead pipeline-by-stage counts (NEW/CONTACTED/QUOTED) as
   * ordered, labelled counts for the "My leads" widget (Req 6.6, lead-management).
   */
  protected readonly salespersonLeadStages = computed<StatusCount[]>(() => {
    const map = this.summary()?.salesperson?.leadPipeline;
    if (!map) {
      return [];
    }
    const order = ['NEW', 'CONTACTED', 'QUOTED'];
    return order
      .filter((k) => k in map)
      .map((key) => ({ key, label: this.humanizeStatus(key), value: map[key] ?? 0 }));
  });

  /** The admin leads/conversion overview, when present (Req 6.6, lead-management). */
  protected readonly adminLeads = computed(() => this.summary()?.admin?.leads ?? null);

  /** The admin lead pipeline-by-stage counts as ordered, labelled counts. */
  protected readonly adminLeadStages = computed<StatusCount[]>(() => {
    const map = this.adminLeads()?.pipelineByStage;
    if (!map) {
      return [];
    }
    const order = ['NEW', 'CONTACTED', 'QUOTED'];
    return order
      .filter((k) => k in map)
      .map((key) => ({ key, label: this.humanizeStatus(key), value: map[key] ?? 0 }));
  });

  /** The salesperson's due-follow-up count for the widget badge. */
  protected readonly dueFollowUps = computed(() => this.summary()?.salesperson?.dueFollowUps ?? 0);

  /** The admin statistical-insights overview, when present (Req 11.1, 11.2). */
  protected readonly adminInsights = computed(() => this.summary()?.admin?.insights ?? null);

  /** The DANGER count from the latest computed insights (drives the tile accent/badge). */
  protected readonly insightDanger = computed(
    () => this.adminInsights()?.countsBySeverity?.['DANGER'] ?? 0,
  );

  /** The WARNING count from the latest computed insights. */
  protected readonly insightWarning = computed(
    () => this.adminInsights()?.countsBySeverity?.['WARNING'] ?? 0,
  );

  /** The INFO count from the latest computed insights. */
  protected readonly insightInfo = computed(
    () => this.adminInsights()?.countsBySeverity?.['INFO'] ?? 0,
  );

  /** The total number of insights across all severities (drives the headline number). */
  protected readonly insightTotal = computed(
    () => this.insightDanger() + this.insightWarning() + this.insightInfo(),
  );

  /** Honour reduced-motion by disabling chart animations. */
  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  /** Time-of-day greeting for the welcome hero card. */
  protected readonly greeting = computed(() => {
    const hr = new Date().getHours();
    if (hr < 12) return 'Good morning';
    if (hr < 17) return 'Good afternoon';
    return 'Good evening';
  });

  /**
   * A compact area-sparkline built from the current sales-graph points
   * (real data — no fabrication). Returns the SVG geometry, or null when
   * there aren't enough points to draw a meaningful trend.
   */
  protected readonly salesSpark = computed(() => {
    const points = this.metrics()?.salesGraph.points ?? [];
    const vals = points.map((p) => p.sales);
    if (vals.length < 2) {
      return null;
    }
    const w = 240;
    const h = 56;
    const pad = 3;
    const max = Math.max(1, ...vals);
    const min = Math.min(...vals);
    const range = max - min || 1;
    const n = vals.length;
    const x = (i: number) => pad + (i / (n - 1)) * (w - 2 * pad);
    const y = (v: number) => h - pad - ((v - min) / range) * (h - 2 * pad);
    const line = vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const area =
      `M ${x(0).toFixed(1)},${(h - pad).toFixed(1)} L ` +
      vals.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' L ') +
      ` L ${x(n - 1).toFixed(1)},${(h - pad).toFixed(1)} Z`;
    return { w, h, line, area };
  });

  /**
   * Current- vs previous-period sales totals derived from the loaded sales
   * graph (no extra backend call). Powers the Sales Overview "This period vs
   * Previous period" summary; {@code showPrev} is false when there's no
   * comparable previous window.
   */
  protected readonly periodTotals = computed<{
    current: number;
    previous: number;
    showPrev: boolean;
  } | null>(() => {
    const g = this.metrics()?.salesGraph;
    if (!g || g.points.length === 0) {
      return null;
    }
    const current = g.points.reduce((acc, p) => acc + p.sales, 0);
    const previous = g.points.reduce((acc, p) => acc + p.previousSales, 0);
    return { current, previous, showPrev: g.changeApplicable || previous > 0 };
  });

  /** The display label for the currently selected metrics period. */
  protected periodLabel(): string {
    return this.periodOptions.find((o) => o.value === this.period())?.label ?? '';
  }

  /** Selectable period presets (Req 19.2). */
  protected readonly periodOptions: PeriodOption[] = [
    { value: 'TODAY', label: 'Today' },
    { value: 'YESTERDAY', label: 'Yesterday' },
    { value: 'LAST_7_DAYS', label: 'Last 7 days' },
    { value: 'LAST_30_DAYS', label: 'Last 30 days' },
    { value: 'THIS_MONTH', label: 'This month' },
    { value: 'LAST_MONTH', label: 'Last month' },
    { value: 'QUARTERLY', label: 'Quarterly' },
    { value: 'YEARLY', label: 'Yearly' },
    { value: 'CUSTOM', label: 'Custom' },
  ];

  protected readonly buckets: { value: SalesBucket; label: string }[] = [
    { value: 'DAY', label: 'Day' },
    { value: 'WEEK', label: 'Week' },
    { value: 'MONTH', label: 'Month' },
  ];

  // --- Selection state ----------------------------------------------------
  protected readonly period = signal<MetricsPeriod>('LAST_30_DAYS');
  protected readonly bucket = signal<SalesBucket | null>(null);
  protected readonly customFrom = signal<string>('');
  protected readonly customTo = signal<string>('');

  // --- Data ---------------------------------------------------------------
  protected readonly metrics = signal<DashboardMetrics | null>(null);
  private readonly restLive = signal<LiveStats | null>(null);
  private readonly restActivity = signal<ActivityCards | null>(null);

  // --- UI state -----------------------------------------------------------
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** Live stats prefer the real-time SSE push, falling back to the REST snapshot. */
  protected readonly live = computed<LiveStats | null>(
    () => this.events.liveStats() ?? this.restLive(),
  );

  /** Activity counts prefer the real-time SSE push, falling back to the REST snapshot. */
  protected readonly activity = computed<ActivityCards | null>(
    () => this.events.activity() ?? this.restActivity(),
  );

  /** The resolved SVG chart geometry for the current sales graph, or null when empty. */
  protected readonly chart = computed<ChartModel | null>(() => this.buildChart());

  // --- ApexCharts (A1) ----------------------------------------------------

  /**
   * The revenue-trend area chart (current vs previous period) built from the
   * loaded {@code salesGraph.points} — no extra backend calls. Themed with the
   * Shifa green/gold palette; INR tooltips; animations off under reduced-motion.
   */
  protected readonly revenueChart = computed<RevenueChartOptions | null>(() => {
    const g = this.metrics()?.salesGraph;
    if (!g || g.points.length === 0) {
      return null;
    }
    const categories = g.points.map((p) => this.shortLabel(p.label));
    const showPrev = g.changeApplicable || g.points.some((p) => p.previousSales > 0);
    const series: ApexAxisChartSeries = [
      { name: 'Current period', data: g.points.map((p) => Math.round(p.sales)) },
    ];
    if (showPrev) {
      series.push({
        name: 'Previous period',
        data: g.points.map((p) => Math.round(p.previousSales)),
      });
    }
    return {
      series,
      chart: {
        type: 'area',
        height: 300,
        fontFamily: 'inherit',
        toolbar: { show: false },
        zoom: { enabled: false },
        parentHeightOffset: 0,
        animations: { enabled: !this.reducedMotion },
      },
      colors: ['#1f5d3f', '#c9a227'],
      dataLabels: { enabled: false },
      stroke: {
        curve: 'smooth',
        width: showPrev ? [3, 2] : [3],
        dashArray: showPrev ? [0, 5] : [0],
      },
      fill: {
        type: 'gradient',
        gradient: { shadeIntensity: 1, opacityFrom: 0.35, opacityTo: 0.05, stops: [0, 90, 100] },
      },
      xaxis: {
        categories,
        labels: { rotate: -45, hideOverlappingLabels: true, style: { colors: '#6b7c74' } },
        axisBorder: { show: false },
        axisTicks: { show: false },
        tooltip: { enabled: false },
      },
      yaxis: {
        labels: { formatter: (v: number) => this.compact(v), style: { colors: '#6b7c74' } },
      },
      legend: { position: 'top', horizontalAlign: 'right', fontFamily: 'inherit' },
      tooltip: { theme: 'light', y: { formatter: (v: number) => this.inr(v) } },
      grid: {
        borderColor: 'rgba(15,51,36,0.08)',
        strokeDashArray: 4,
        padding: { left: 8, right: 8 },
      },
      markers: { size: 0, hover: { size: 4 } },
    };
  });

  /** The non-zero order-status segments, in a consistent tone order (A1/A2). */
  protected readonly statusSegments = computed<StatusSegment[]>(() => {
    const c = this.metrics()?.cards;
    if (!c) {
      return [];
    }
    const all: StatusSegment[] = [
      { label: 'Pending', value: c.pendingOrders, color: '#f59f00', status: OrderStatus.PENDING_ADMIN_APPROVAL },
      { label: 'Packed', value: c.packedOrders, color: '#0ca678', status: OrderStatus.PACKED },
      { label: 'Dispatched', value: c.dispatchedOrders, color: '#4263eb', status: OrderStatus.DISPATCHED },
      { label: 'Delivered', value: c.deliveredOrders, color: '#2fb344', status: OrderStatus.DELIVERED },
      { label: 'RTO', value: c.rtoCount, color: '#f76707', status: OrderStatus.RTO },
      { label: 'Redispatch', value: c.redispatchCount, color: '#d63939', status: OrderStatus.REDISPATCH },
    ];
    return all.filter((s) => s.value > 0);
  });

  /**
   * The orders-by-status donut, using the shared status-tone colours so it
   * matches the StatusBadge language. Clicking a segment drills down into the
   * Orders list pre-filtered by that status (A2).
   */
  protected readonly statusChart = computed<StatusChartOptions | null>(() => {
    const segs = this.statusSegments();
    if (segs.length === 0) {
      return null;
    }
    const total = segs.reduce((acc, s) => acc + s.value, 0);
    return {
      series: segs.map((s) => s.value),
      labels: segs.map((s) => s.label),
      colors: segs.map((s) => s.color),
      chart: {
        type: 'donut',
        height: 300,
        fontFamily: 'inherit',
        animations: { enabled: !this.reducedMotion },
        events: {
          dataPointSelection: (_e: unknown, _ctx: unknown, cfg: { dataPointIndex: number }) => {
            const seg = this.statusSegments()[cfg.dataPointIndex];
            if (seg) {
              this.drillDown(seg.status);
            }
          },
        },
      },
      legend: { position: 'bottom', fontFamily: 'inherit' },
      dataLabels: { enabled: true, formatter: (val: number) => `${Math.round(val)}%` },
      stroke: { width: 2, colors: ['#fff'] },
      plotOptions: {
        pie: {
          donut: {
            size: '68%',
            labels: {
              show: true,
              total: { show: true, label: 'Orders', formatter: () => this.count(total) },
            },
          },
        },
      },
      tooltip: { theme: 'light', y: { formatter: (v: number) => this.count(v) } },
      responsive: [{ breakpoint: 480, options: { legend: { position: 'bottom' } } }],
    };
  });

  // --- Drill-down (A2) ----------------------------------------------------

  /**
   * Navigates to the Orders list pre-filtered by an order status, carrying the
   * current dashboard date window as {@code from}/{@code to} so the drill-down
   * lands scoped to the same period.
   */
  drillDown(status: OrderStatus | string): void {
    const m = this.metrics();
    const queryParams: Record<string, string> = { status };
    const from = this.dateOnly(m?.from);
    const to = this.dateOnly(m?.to);
    if (from) {
      queryParams['from'] = from;
    }
    if (to) {
      queryParams['to'] = to;
    }
    this.router.navigate(['/orders'], { queryParams });
  }

  /** Navigates to the Orders list scoped only to the current date window. */
  drillAll(): void {
    const m = this.metrics();
    const queryParams: Record<string, string> = {};
    const from = this.dateOnly(m?.from);
    const to = this.dateOnly(m?.to);
    if (from) {
      queryParams['from'] = from;
    }
    if (to) {
      queryParams['to'] = to;
    }
    this.router.navigate(['/orders'], { queryParams });
  }

  /** Extracts the yyyy-MM-dd date part from an ISO date/datetime, or null. */
  private dateOnly(value: string | null | undefined): string | null {
    if (!value) {
      return null;
    }
    const match = /^(\d{4}-\d{2}-\d{2})/.exec(value);
    return match ? match[1] : null;
  }

  ngOnInit(): void {
    // Payment Verifier has no dashboard content (their work lives on the payment
    // queue) — send them straight to their home so they don't land on an empty
    // dashboard when '' redirects here or after login.
    if (this.role() === Role.PAYMENT_VERIFIER) {
      void this.router.navigate(['/payments']);
      return;
    }
    // A CA works from the dedicated GST/accounting dashboard — send them there
    // rather than the empty role-less operational dashboard (CA GST dashboard, Req 1.4).
    if (this.role() === Role.CA) {
      void this.router.navigate(['/ca/gst']);
      return;
    }
    // The role-shaped summary is available to every operational role (Req 3.1).
    this.loadSummary();
    // The rich metrics dashboard + live SSE feed are ADMIN-only endpoints, so
    // only an admin fetches them; other roles render their summary card set
    // and never call the admin-only surface (avoiding 403s).
    if (this.isAdmin()) {
      this.reload();
      this.refreshLiveAndActivity();
      // Open the real-time stream; the service is idempotent and no-ops when
      // unauthenticated or EventSource is unavailable.
      this.events.connect();
    }
  }

  ngOnDestroy(): void {
    this.events.disconnect();
  }

  /** Loads the role-shaped dashboard summary for the current user (Req 3.1–3.6). */
  loadSummary(): void {
    this.summaryLoading.set(true);
    this.summaryError.set(null);
    this.service.roleSummary().subscribe({
      next: (s) => {
        this.summary.set(s);
        this.summaryLoading.set(false);
      },
      error: () => {
        this.summaryError.set('Could not load your dashboard. Please try again.');
        this.summaryLoading.set(false);
      },
    });
    // My Day + win-back are salesperson-only self-service widgets.
    if (this.isSalesperson()) {
      this.loadMyDay();
    }
    // Team Leads get a server-scoped member count and headline team metrics.
    if (this.isTeamLead()) {
      this.loadTeamPerformance();
    }
  }

  /** Turns a status→count map (keyed by backend status name) into sorted, labelled counts. */
  private toStatusCounts(map: Record<string, number> | null | undefined): StatusCount[] {
    if (!map) {
      return [];
    }
    return Object.entries(map)
      .map(([key, value]) => ({ key, label: this.humanizeStatus(key), value: value ?? 0 }))
      .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label));
  }

  /** Humanises a backend status name ("HANDED_TO_DELIVERY" → "Handed To Delivery"). */
  humanizeStatus(status: string): string {
    return status
      .toLowerCase()
      .replaceAll('_', ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase())
      .replace(/\bRto\b/i, 'RTO')
      .replace(/\bCod\b/i, 'COD');
  }

  // --- Period selection ---------------------------------------------------

  selectPeriod(value: MetricsPeriod): void {
    if (this.period() === value) {
      return;
    }
    this.period.set(value);
    // A custom range only reloads once both bounds are set (see applyCustom()).
    if (value !== 'CUSTOM') {
      this.reload();
    }
  }

  selectBucket(value: SalesBucket | null): void {
    this.bucket.set(value);
    this.reload();
  }

  onCustomFrom(value: string): void {
    this.customFrom.set(value);
  }

  onCustomTo(value: string): void {
    this.customTo.set(value);
  }

  applyCustom(): void {
    if (this.period() === 'CUSTOM' && this.customFrom() && this.customTo()) {
      this.reload();
    }
  }

  /** Whether the custom-range apply button should be enabled. */
  protected canApplyCustom(): boolean {
    return !!this.customFrom() && !!this.customTo() && this.customFrom() <= this.customTo();
  }

  // --- Loading ------------------------------------------------------------

  reload(): void {
    const period = this.period();
    if (period === 'CUSTOM' && !this.canApplyCustom()) {
      // Wait for a valid custom range before fetching.
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.service
      .metrics(
        period,
        this.bucket(),
        period === 'CUSTOM' ? this.customFrom() : null,
        period === 'CUSTOM' ? this.customTo() : null,
      )
      .subscribe({
        next: (m) => {
          this.metrics.set(m);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load dashboard metrics. Please try again.');
          this.loading.set(false);
        },
      });
  }

  private refreshLiveAndActivity(): void {
    this.service.liveStats().subscribe({
      next: (s) => this.restLive.set(s),
      error: () => {
        /* live stats also arrive over SSE; ignore a transient REST error */
      },
    });
    this.service.activity().subscribe({
      next: (a) => this.restActivity.set(a),
      error: () => {
        /* activity also arrives over SSE; ignore a transient REST error */
      },
    });
  }

  // --- Notification feed --------------------------------------------------

  clearNotifications(): void {
    this.events.clearNotifications();
  }

  // --- Formatting helpers -------------------------------------------------

  /** Formats a number as Indian Rupees with two decimals. */
  inr(value: number | null | undefined): string {
    const n = typeof value === 'number' ? value : 0;
    return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }

  /** Formats an integer count with grouping. */
  count(value: number | null | undefined): string {
    const n = typeof value === 'number' ? value : 0;
    return n.toLocaleString('en-IN');
  }

  /** Formats the previous-period % change with sign, or an em dash when N/A. */
  changeText(): string {
    const g = this.metrics()?.salesGraph;
    if (!g || !g.changeApplicable || g.changePercent === null) {
      return '—';
    }
    const sign = g.changePercent > 0 ? '+' : '';
    return `${sign}${g.changePercent.toFixed(2)}%`;
  }

  /** The direction of the sales change, for colour/arrow styling. */
  changeDirection(): 'up' | 'down' | 'flat' {
    const g = this.metrics()?.salesGraph;
    if (!g || !g.changeApplicable || g.changePercent === null || g.changePercent === 0) {
      return 'flat';
    }
    return g.changePercent > 0 ? 'up' : 'down';
  }

  // --- SVG chart layout ---------------------------------------------------

  private buildChart(): ChartModel | null {
    const graph = this.metrics()?.salesGraph;
    if (!graph || graph.points.length === 0) {
      return null;
    }
    const points = graph.points;
    const width = 760;
    const height = 260;
    const padL = 64;
    const padR = 16;
    const padT = 16;
    const padB = 44;
    const innerW = width - padL - padR;
    const innerH = height - padT - padB;
    const n = points.length;

    const maxVal = Math.max(1, ...points.map((p) => Math.max(p.sales, p.previousSales)));
    const slot = innerW / n;
    const barW = Math.max(4, Math.min(40, slot * 0.55));
    const centerX = (i: number) => padL + slot * i + slot / 2;
    const yFor = (v: number) => padT + innerH - (v / maxVal) * innerH;

    const bars: ChartBar[] = points.map((p, i) => {
      const y = yFor(p.sales);
      return {
        x: centerX(i) - barW / 2,
        y,
        w: barW,
        h: padT + innerH - y,
        label: p.label,
        sales: p.sales,
        previousSales: p.previousSales,
      };
    });

    const previousDots = points.map((p, i) => ({ x: centerX(i), y: yFor(p.previousSales) }));
    const previousLine = previousDots.map((d) => `${d.x},${d.y}`).join(' ');

    const gridLines = [0, 0.25, 0.5, 0.75, 1].map((f) => ({
      y: padT + innerH - f * innerH,
      label: this.compact(maxVal * f),
    }));

    // Thin the x-axis labels so they never overlap on long windows.
    const stepEvery = Math.max(1, Math.ceil(n / 8));
    const axisLabels = points
      .map((p, i) => ({ x: centerX(i), label: p.label, i }))
      .filter((e) => e.i % stepEvery === 0 || e.i === n - 1)
      .map((e) => ({ x: e.x, label: this.shortLabel(e.label) }));

    return { width, height, bars, previousLine, previousDots, gridLines, axisLabels };
  }

  /** Compact axis number (e.g. 12.5k) so the y-axis stays readable. */
  private compact(value: number): string {
    if (value >= 1_00_00_000) {
      return `${(value / 1_00_00_000).toFixed(1)}Cr`;
    }
    if (value >= 1_00_000) {
      return `${(value / 1_00_000).toFixed(1)}L`;
    }
    if (value >= 1_000) {
      return `${(value / 1_000).toFixed(1)}k`;
    }
    return `${Math.round(value)}`;
  }

  /** Trims an ISO date / week / month bucket label to something compact. */
  private shortLabel(label: string): string {
    // Daily labels are ISO dates (yyyy-MM-dd) -> show MM-dd.
    const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(label);
    if (iso) {
      return `${iso[2]}-${iso[3]}`;
    }
    // Weekly labels look like "Wk 2024-05-06" -> show the date part MM-dd.
    const wk = /^Wk\s+(\d{4})-(\d{2})-(\d{2})$/.exec(label);
    if (wk) {
      return `${wk[2]}-${wk[3]}`;
    }
    return label;
  }
}
