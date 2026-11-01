import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexGrid,
  ApexPlotOptions,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { formatDateLabel } from '../shared/date-label.util';
import { formatInr } from '../shared/inr.pipe';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import {
  MAX_DATASET_DAYS,
  PERIOD_PRESETS,
  PeriodKey,
  inclusiveDays,
  istToday,
  periodProblem,
  resolvePeriod,
} from './analytics-period.util';
import { AnalyticsService } from './analytics.service';
import {
  CustomerSegmentsReport,
  DeliveryPerformanceReport,
  ForecastReport,
  LeadFunnel,
  OperationsReport,
  OverviewReport,
  PointersReport,
  ProductPerformanceReport,
  RetentionReport,
  SalesTargetRow,
} from './analytics.model';
import { AcquisitionTabComponent } from './tabs/acquisition-tab.component';
import { CustomersTabComponent } from './tabs/customers-tab.component';
import { DatasetsTabComponent } from './tabs/datasets-tab.component';
import { OperationsTabComponent } from './tabs/operations-tab.component';
import { OverviewTabComponent } from './tabs/overview-tab.component';
import { ProductsTabComponent } from './tabs/products-tab.component';

type AnalyticsTab =
  | 'overview'
  | 'customers'
  | 'retention'
  | 'products'
  | 'acquisition'
  | 'operations'
  | 'delivery'
  | 'forecast'
  | 'targets'
  | 'data';

/** Tabs that read the period chosen at the top of the page. The others cover all time or a fixed window. */
const PERIOD_TABS: readonly AnalyticsTab[] = ['overview', 'products', 'acquisition', 'operations', 'data'];

const TAB_KEYS: readonly AnalyticsTab[] = [
  'overview',
  'customers',
  'retention',
  'products',
  'acquisition',
  'operations',
  'delivery',
  'forecast',
  'targets',
  'data',
];

function isTab(value: string | null): value is AnalyticsTab {
  return value !== null && (TAB_KEYS as readonly string[]).includes(value);
}

/** ApexCharts bar option bundle used by both the reorder + demand charts. */
interface BarOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  plotOptions: ApexPlotOptions;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis;
  grid: ApexGrid;
  tooltip: ApexTooltip;
}

/**
 * Admin analytics suite. Growth analytics (overview with key pointers, customers, products,
 * acquisition, operations and data downloads) sit beside the original sales targets (§6.1),
 * retention (§6.3), forecast (§6.5) and delivery performance tabs. ADMIN-only route.
 *
 * <p>The page period (default last 30 days, IST) drives the overview, products, acquisition,
 * operations and data tabs. Customers, retention, delivery, forecast and targets do not use it.
 * The selected tab is kept in {@code ?tab=} so a pointer on the overview can link straight to
 * the tab that explains it.
 */
@Component({
  selector: 'admin-analytics',
  standalone: true,
  imports: [
    FormsModule,
    NgApexchartsModule,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    OverviewTabComponent,
    CustomersTabComponent,
    ProductsTabComponent,
    AcquisitionTabComponent,
    OperationsTabComponent,
    DatasetsTabComponent,
  ],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.css',
})
export class AnalyticsComponent implements OnInit {
  private readonly service = inject(AnalyticsService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  protected readonly tabs: { key: AnalyticsTab; label: string; icon: string }[] = [
    { key: 'overview', label: 'Overview', icon: 'ti-layout-dashboard' },
    { key: 'customers', label: 'Customers', icon: 'ti-heart-handshake' },
    { key: 'retention', label: 'Retention', icon: 'ti-users-group' },
    { key: 'products', label: 'Products', icon: 'ti-package' },
    { key: 'acquisition', label: 'Acquisition', icon: 'ti-speakerphone' },
    { key: 'operations', label: 'Operations', icon: 'ti-truck-loading' },
    { key: 'delivery', label: 'Delivery', icon: 'ti-truck-delivery' },
    { key: 'forecast', label: 'Forecast', icon: 'ti-chart-dots' },
    { key: 'targets', label: 'Targets & Incentives', icon: 'ti-target' },
    { key: 'data', label: 'Data', icon: 'ti-database-export' },
  ];
  protected readonly activeTab = signal<AnalyticsTab>('overview');

  // --- Page period (overview, products, acquisition, operations, data) -----
  protected readonly periodPresets = PERIOD_PRESETS;
  protected readonly periodKey = signal<PeriodKey>('30d');
  /** The period the reports were requested for (IST, yyyy-MM-dd). */
  protected readonly from = signal<string>(resolvePeriod('30d', istToday()).from);
  protected readonly to = signal<string>(resolvePeriod('30d', istToday()).to);
  /** What the user has typed in the custom boxes, applied only when they press Apply. */
  protected readonly customFrom = signal<string>(this.from());
  protected readonly customTo = signal<string>(this.to());
  protected readonly periodError = signal<string | null>(null);
  protected readonly usesPeriod = computed(() => PERIOD_TABS.includes(this.activeTab()));
  protected readonly periodLabel = computed(
    () => `${formatDateLabel(this.from())} – ${formatDateLabel(this.to())}`,
  );
  protected readonly periodDays = computed(() => inclusiveDays(this.from(), this.to()));
  /** Bumped whenever the period changes, so a late reply for an old period is dropped. */
  private periodVersion = 0;

  // --- Growth analytics ---------------------------------------------------
  protected readonly overview = signal<OverviewReport | null>(null);
  protected readonly overviewLoading = signal(false);
  protected readonly overviewError = signal<string | null>(null);

  protected readonly pointers = signal<PointersReport | null>(null);
  protected readonly pointersLoading = signal(false);
  protected readonly pointersError = signal<string | null>(null);

  protected readonly customers = signal<CustomerSegmentsReport | null>(null);
  protected readonly customersLoading = signal(false);
  protected readonly customersError = signal<string | null>(null);

  protected readonly products = signal<ProductPerformanceReport | null>(null);
  protected readonly productsLoading = signal(false);
  protected readonly productsError = signal<string | null>(null);

  protected readonly operations = signal<OperationsReport | null>(null);
  protected readonly operationsLoading = signal(false);
  protected readonly operationsError = signal<string | null>(null);

  protected readonly funnel = signal<LeadFunnel | null>(null);
  protected readonly funnelLoading = signal(false);
  protected readonly funnelError = signal<string | null>(null);

  /** The acquisition tab needs both the overview (revenue by source) and the lead funnel. */
  protected readonly acquisitionLoading = computed(() => this.overviewLoading() || this.funnelLoading());
  protected readonly acquisitionError = computed(() => this.overviewError() ?? this.funnelError());

  // --- Targets (§6.1) -----------------------------------------------------
  protected readonly targetRows = signal<SalesTargetRow[]>([]);
  protected readonly targetsLoading = signal(false);
  protected readonly targetsError = signal<string | null>(null);
  protected readonly month = signal<string>(this.currentMonth());
  protected readonly savingId = signal<number | null>(null);
  /** Editable target/incentive values keyed by salespersonId. */
  protected readonly edits = signal<Record<number, { target: number | null; incentive: number | null }>>({});

  // --- Targets paging (client-side, 10/page) ------------------------------
  protected readonly targetsPage = signal(0);
  protected readonly targetsSize = signal(readPageSize('analyticsTargets', 10));
  protected readonly targetsTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.targetRows().length / this.targetsSize())),
  );
  protected readonly targetPageRows = computed<SalesTargetRow[]>(() => {
    const s = this.targetsPage() * this.targetsSize();
    return this.targetRows().slice(s, s + this.targetsSize());
  });
  setTargetsPage(p: number): void {
    this.targetsPage.set(p);
  }
  setTargetsSize(s: number): void {
    this.targetsSize.set(s);
    writePageSize('analyticsTargets', s);
    this.targetsPage.set(0);
  }

  // --- Retention (§6.3) ---------------------------------------------------
  protected readonly retention = signal<RetentionReport | null>(null);
  protected readonly retentionLoading = signal(false);
  protected readonly retentionError = signal<string | null>(null);
  protected readonly cohortMonths = signal(6);

  // --- Forecast (§6.5) ----------------------------------------------------
  protected readonly forecast = signal<ForecastReport | null>(null);
  protected readonly forecastLoading = signal(false);
  protected readonly forecastError = signal<string | null>(null);

  // --- Delivery performance (ENHANCEMENT 3.3) -----------------------------
  protected readonly delivery = signal<DeliveryPerformanceReport | null>(null);
  protected readonly deliveryLoading = signal(false);
  protected readonly deliveryError = signal<string | null>(null);

  private targetsLoaded = false;
  /** True once the first address-bar state has been applied. */
  private started = false;

  /** The data tab allows a shorter period than the reports; warn instead of letting the download fail. */
  protected readonly datasetPeriodTooLong = computed(
    () => this.activeTab() === 'data' && this.periodDays() > MAX_DATASET_DAYS,
  );
  protected readonly maxDatasetDays = MAX_DATASET_DAYS;

  ngOnInit(): void {
    // The URL decides the tab. A link such as /analytics?tab=customers (from a pointer, or a
    // bookmark) opens that tab; no parameter means the overview.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const requested = params.get('tab');
      const tab: AnalyticsTab = isTab(requested) ? requested : 'overview';
      if (tab !== this.activeTab() || !this.started) {
        this.started = true;
        this.activate(tab);
      }
    });
  }

  /** A tab button was pressed: show it and keep the address bar in step. */
  selectTab(tab: AnalyticsTab): void {
    this.activate(tab);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** Shows a tab and loads what it needs the first time it is opened. */
  private activate(tab: AnalyticsTab): void {
    this.activeTab.set(tab);
    switch (tab) {
      case 'overview':
        if (!this.overview() && !this.overviewLoading()) {
          this.loadOverview();
        }
        break;
      case 'customers':
        if (!this.customers() && !this.customersLoading()) {
          this.loadCustomers();
        }
        break;
      case 'retention':
        if (!this.retention() && !this.retentionLoading()) {
          this.loadRetention();
        }
        break;
      case 'products':
        if (!this.products() && !this.productsLoading()) {
          this.loadProducts();
        }
        break;
      case 'acquisition':
        this.loadAcquisition();
        break;
      case 'operations':
        if (!this.operations() && !this.operationsLoading()) {
          this.loadOperations();
        }
        break;
      case 'delivery':
        if (!this.delivery() && !this.deliveryLoading()) {
          this.loadDelivery();
        }
        break;
      case 'forecast':
        if (!this.forecast() && !this.forecastLoading()) {
          this.loadForecast();
        }
        break;
      case 'targets':
        if (!this.targetsLoaded) {
          this.loadTargets();
        }
        break;
      case 'data':
        // The data tab loads its own catalogue when it appears.
        break;
    }
  }

  // --- Period -------------------------------------------------------------

  /** A preset was chosen. "Custom" only reveals the date boxes; Apply runs it. */
  choosePeriod(key: PeriodKey): void {
    this.periodKey.set(key);
    if (key === 'custom') {
      this.customFrom.set(this.from());
      this.customTo.set(this.to());
      this.periodError.set(null);
      return;
    }
    const range = resolvePeriod(key, istToday());
    this.periodError.set(null);
    this.setPeriod(range.from, range.to);
  }

  /** Apply the dates typed into the custom boxes. */
  applyCustomPeriod(): void {
    const from = this.customFrom();
    const to = this.customTo();
    const problem = periodProblem(from, to, this.activeTab() === 'data' ? MAX_DATASET_DAYS : undefined);
    if (problem) {
      this.periodError.set(problem);
      return;
    }
    this.periodError.set(null);
    this.setPeriod(from, to);
  }

  /** Switches to a new period: forgets the old period's reports and reloads the open tab. */
  private setPeriod(from: string, to: string): void {
    if (from === this.from() && to === this.to()) {
      return;
    }
    this.periodVersion++;
    this.from.set(from);
    this.to.set(to);
    this.overview.set(null);
    this.pointers.set(null);
    this.products.set(null);
    this.operations.set(null);
    this.funnel.set(null);
    this.overviewLoading.set(false);
    this.pointersLoading.set(false);
    this.productsLoading.set(false);
    this.operationsLoading.set(false);
    this.funnelLoading.set(false);
    this.overviewError.set(null);
    this.pointersError.set(null);
    this.productsError.set(null);
    this.operationsError.set(null);
    this.funnelError.set(null);
    if (PERIOD_TABS.includes(this.activeTab())) {
      this.activate(this.activeTab());
    }
  }

  // --- Overview, pointers, acquisition ------------------------------------

  /** The overview and the pointers load together; the page shows the overview as soon as it lands. */
  loadOverview(): void {
    const version = this.periodVersion;
    this.overviewLoading.set(true);
    this.overviewError.set(null);
    this.service.overview(this.from(), this.to()).subscribe({
      next: (r) => {
        if (version !== this.periodVersion) {
          return;
        }
        this.overview.set(r);
        this.overviewLoading.set(false);
      },
      error: () => {
        if (version !== this.periodVersion) {
          return;
        }
        this.overviewError.set('Could not load the overview.');
        this.overviewLoading.set(false);
      },
    });
    this.loadPointers();
  }

  loadPointers(): void {
    const version = this.periodVersion;
    this.pointersLoading.set(true);
    this.pointersError.set(null);
    this.service.pointers(this.from(), this.to()).subscribe({
      next: (r) => {
        if (version !== this.periodVersion) {
          return;
        }
        this.pointers.set(r);
        this.pointersLoading.set(false);
      },
      error: () => {
        if (version !== this.periodVersion) {
          return;
        }
        this.pointersError.set('Could not load the key pointers.');
        this.pointersLoading.set(false);
      },
    });
  }

  /** Loads whichever of the overview and the lead funnel is missing. */
  loadAcquisition(): void {
    if (!this.overview() && !this.overviewLoading()) {
      this.loadOverview();
    }
    if (!this.funnel() && !this.funnelLoading()) {
      this.loadFunnel();
    }
  }

  private loadFunnel(): void {
    const version = this.periodVersion;
    this.funnelLoading.set(true);
    this.funnelError.set(null);
    this.service.leadFunnel(this.from(), this.to()).subscribe({
      next: (r) => {
        if (version !== this.periodVersion) {
          return;
        }
        this.funnel.set(r);
        this.funnelLoading.set(false);
      },
      error: () => {
        if (version !== this.periodVersion) {
          return;
        }
        this.funnelError.set('Could not load the lead funnel.');
        this.funnelLoading.set(false);
      },
    });
  }

  // --- Customers, products, operations ------------------------------------

  loadCustomers(): void {
    this.customersLoading.set(true);
    this.customersError.set(null);
    this.service.customers().subscribe({
      next: (r) => {
        this.customers.set(r);
        this.customersLoading.set(false);
      },
      error: () => {
        this.customersError.set('Could not load customer segments.');
        this.customersLoading.set(false);
      },
    });
  }

  loadProducts(): void {
    const version = this.periodVersion;
    this.productsLoading.set(true);
    this.productsError.set(null);
    this.service.products(this.from(), this.to()).subscribe({
      next: (r) => {
        if (version !== this.periodVersion) {
          return;
        }
        this.products.set(r);
        this.productsLoading.set(false);
      },
      error: () => {
        if (version !== this.periodVersion) {
          return;
        }
        this.productsError.set('Could not load product performance.');
        this.productsLoading.set(false);
      },
    });
  }

  loadOperations(): void {
    const version = this.periodVersion;
    this.operationsLoading.set(true);
    this.operationsError.set(null);
    this.service.operations(this.from(), this.to()).subscribe({
      next: (r) => {
        if (version !== this.periodVersion) {
          return;
        }
        this.operations.set(r);
        this.operationsLoading.set(false);
      },
      error: () => {
        if (version !== this.periodVersion) {
          return;
        }
        this.operationsError.set('Could not load operations analytics.');
        this.operationsLoading.set(false);
      },
    });
  }

  // --- Delivery performance -----------------------------------------------

  loadDelivery(): void {
    this.deliveryLoading.set(true);
    this.deliveryError.set(null);
    this.service.deliveryPerformance().subscribe({
      next: (r) => {
        this.delivery.set(r);
        this.deliveryLoading.set(false);
      },
      error: () => {
        this.deliveryError.set('Could not load delivery performance.');
        this.deliveryLoading.set(false);
      },
    });
  }

  /** Colour class for a delivery success rate: green ≥80, amber ≥50, red below. */
  deliveryRateClass(pct: number): string {
    if (pct >= 80) {
      return 'text-success';
    }
    if (pct >= 50) {
      return 'text-warning';
    }
    return 'text-danger';
  }

  // --- Targets ------------------------------------------------------------

  loadTargets(): void {
    this.targetsLoading.set(true);
    this.targetsError.set(null);
    this.service.targets(this.month()).subscribe({
      next: (rows) => {
        this.targetRows.set(rows);
        const map: Record<number, { target: number | null; incentive: number | null }> = {};
        for (const r of rows) {
          map[r.salespersonId] = {
            target: r.targetAmount ? Number(r.targetAmount) : null,
            incentive: r.incentivePct ? Number(r.incentivePct) : null,
          };
        }
        this.edits.set(map);
        this.targetsPage.set(0);
        this.targetsLoaded = true;
        this.targetsLoading.set(false);
      },
      error: () => {
        this.targetsError.set('Could not load targets. Please try again.');
        this.targetsLoading.set(false);
      },
    });
  }

  onMonthChange(value: string): void {
    this.month.set(value || this.currentMonth());
    this.loadTargets();
  }

  saveTarget(row: SalesTargetRow): void {
    const edit = this.edits()[row.salespersonId];
    if (!edit || edit.target === null || edit.target === undefined || this.savingId() !== null) {
      return;
    }
    this.savingId.set(row.salespersonId);
    this.service
      .setTarget({
        salespersonId: row.salespersonId,
        month: this.month(),
        targetAmount: Number(edit.target),
        incentivePct: edit.incentive === null || edit.incentive === undefined ? null : Number(edit.incentive),
      })
      .subscribe({
        next: (updated) => {
          this.targetRows.update((rows) =>
            rows.map((r) => (r.salespersonId === updated.salespersonId ? updated : r)),
          );
          this.savingId.set(null);
          this.toasts.success(`Target saved for ${row.salespersonName}`);
        },
        error: () => {
          this.savingId.set(null);
          this.toasts.error('Could not save the target.');
        },
      });
  }

  setEdit(id: number, field: 'target' | 'incentive', value: string): void {
    const num = value === '' ? null : Number(value);
    this.edits.update((m) => ({
      ...m,
      [id]: { ...(m[id] ?? { target: null, incentive: null }), [field]: num },
    }));
  }

  // --- Retention ----------------------------------------------------------

  loadRetention(): void {
    this.retentionLoading.set(true);
    this.retentionError.set(null);
    this.service.retention(this.cohortMonths()).subscribe({
      next: (r) => {
        this.retention.set(r);
        this.retentionLoading.set(false);
      },
      error: () => {
        this.retentionError.set('Could not load retention analytics.');
        this.retentionLoading.set(false);
      },
    });
  }

  setCohortMonths(value: string): void {
    this.cohortMonths.set(Number(value) || 6);
    this.loadRetention();
  }

  /** Tabler background class reflecting a retention/attainment percentage. */
  heatClass(pct: number): string {
    if (pct >= 60) {
      return 'bg-green-lt';
    }
    if (pct >= 30) {
      return 'bg-lime-lt';
    }
    if (pct >= 15) {
      return 'bg-yellow-lt';
    }
    if (pct > 0) {
      return 'bg-orange-lt';
    }
    return '';
  }

  protected readonly reorderChart = computed<BarOptions | null>(() => {
    const r = this.retention();
    if (!r || r.reorderBuckets.length === 0) {
      return null;
    }
    return this.barOptions(
      'Customers',
      r.reorderBuckets.map((b) => b.label),
      r.reorderBuckets.map((b) => b.count),
      '#1f5d3f',
    );
  });

  // --- Forecast -----------------------------------------------------------

  loadForecast(): void {
    this.forecastLoading.set(true);
    this.forecastError.set(null);
    this.service.forecast().subscribe({
      next: (r) => {
        this.forecast.set(r);
        this.demandPage.set(0);
        this.forecastLoading.set(false);
      },
      error: () => {
        this.forecastError.set('Could not load the forecast.');
        this.forecastLoading.set(false);
      },
    });
  }

  // --- Forecast top-demand table paging (client-side, 10/page) ------------
  protected readonly demandPage = signal(0);
  protected readonly demandSize = signal(readPageSize('analyticsForecast', 10));
  private readonly demandRows = computed(() => this.forecast()?.topDemand ?? []);
  protected readonly demandTotal = computed(() => this.demandRows().length);
  protected readonly demandTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.demandRows().length / this.demandSize())),
  );
  protected readonly demandPageRows = computed(() => {
    const s = this.demandPage() * this.demandSize();
    return this.demandRows().slice(s, s + this.demandSize());
  });
  setDemandPage(p: number): void {
    this.demandPage.set(p);
  }
  setDemandSize(s: number): void {
    this.demandSize.set(s);
    writePageSize('analyticsForecast', s);
    this.demandPage.set(0);
  }

  // --- Delivery-performance slices (courier / state / pincode band) --------
  // Pincode bands can run to a few hundred rows, so every slice shares one page size and
  // keeps its own page index (keyed by slice title). Short slices show no pager.
  protected readonly bandSize = signal(readPageSize('analyticsBands', 10));
  private readonly bandPages = signal<Record<string, number>>({});

  protected bandPage(key: string, total: number): number {
    const last = Math.max(0, Math.ceil(total / this.bandSize()) - 1);
    return Math.min(this.bandPages()[key] ?? 0, last);
  }
  protected bandTotalPages(total: number): number {
    return Math.max(1, Math.ceil(total / this.bandSize()));
  }
  protected bandRows<T>(key: string, rows: readonly T[]): T[] {
    const size = this.bandSize();
    const start = this.bandPage(key, rows.length) * size;
    return rows.slice(start, start + size);
  }
  protected setBandPage(key: string, page: number): void {
    this.bandPages.update((m) => ({ ...m, [key]: Math.max(0, page) }));
  }
  protected setBandSize(size: number): void {
    this.bandSize.set(size);
    writePageSize('analyticsBands', size);
    this.bandPages.set({});
  }

  protected readonly demandChart = computed<BarOptions | null>(() => {
    const r = this.forecast();
    if (!r || r.topDemand.length === 0) {
      return null;
    }
    const top = r.topDemand.slice(0, 10);
    return this.barOptions(
      `Projected units (next ${r.horizonDays}d)`,
      top.map((d) => d.productName),
      top.map((d) => d.projectedUnits),
      '#0284c7',
    );
  });

  // --- Shared helpers -----------------------------------------------------

  private barOptions(name: string, categories: string[], values: number[], color: string): BarOptions {
    return {
      series: [{ name, data: values }],
      chart: {
        type: 'bar',
        height: 300,
        fontFamily: 'inherit',
        toolbar: { show: false },
        animations: { enabled: !this.reducedMotion },
      },
      colors: [color],
      dataLabels: { enabled: false },
      plotOptions: { bar: { borderRadius: 6, columnWidth: '55%', horizontal: false } },
      xaxis: {
        categories,
        labels: { rotate: -45, hideOverlappingLabels: true, trim: true, style: { colors: '#6b7c74' } },
        axisBorder: { show: false },
        axisTicks: { show: false },
      },
      yaxis: { labels: { style: { colors: '#6b7c74' } } },
      grid: { borderColor: 'rgba(15,51,36,0.08)', strokeDashArray: 4 },
      tooltip: { theme: 'light' },
    };
  }

  money(value: string | number | null | undefined): string {
    return formatInr(value, 0);
  }

  attainmentClass(pct: number | null): string {
    if (pct === null) {
      return 'text-secondary';
    }
    if (pct >= 100) {
      return 'text-success';
    }
    if (pct >= 60) {
      return 'text-warning';
    }
    return 'text-danger';
  }

  private currentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }
}
