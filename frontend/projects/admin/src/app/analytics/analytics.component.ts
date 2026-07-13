import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
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
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { AnalyticsService } from './analytics.service';
import { ForecastReport, RetentionReport, SalesTargetRow } from './analytics.model';

type AnalyticsTab = 'targets' | 'retention' | 'forecast';

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
 * Admin analytics suite (FEATURE-ROADMAP §6): Sales targets & incentives (§6.1),
 * cohort/retention (§6.3), and demand/cash forecasting (§6.5), presented as three
 * tabs. ADMIN-only route.
 */
@Component({
  selector: 'admin-analytics',
  standalone: true,
  imports: [FormsModule, NgApexchartsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.css',
})
export class AnalyticsComponent implements OnInit {
  private readonly service = inject(AnalyticsService);
  private readonly toasts = inject(ToastService);

  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  protected readonly tabs: { key: AnalyticsTab; label: string; icon: string }[] = [
    { key: 'targets', label: 'Targets & Incentives', icon: 'ti-target' },
    { key: 'retention', label: 'Retention', icon: 'ti-users-group' },
    { key: 'forecast', label: 'Forecast', icon: 'ti-chart-dots' },
  ];
  protected readonly activeTab = signal<AnalyticsTab>('targets');

  // --- Targets (§6.1) -----------------------------------------------------
  protected readonly targetRows = signal<SalesTargetRow[]>([]);
  protected readonly targetsLoading = signal(false);
  protected readonly targetsError = signal<string | null>(null);
  protected readonly month = signal<string>(this.currentMonth());
  protected readonly savingId = signal<number | null>(null);
  /** Editable target/incentive values keyed by salespersonId. */
  protected readonly edits = signal<Record<number, { target: number | null; incentive: number | null }>>({});

  // --- Retention (§6.3) ---------------------------------------------------
  protected readonly retention = signal<RetentionReport | null>(null);
  protected readonly retentionLoading = signal(false);
  protected readonly retentionError = signal<string | null>(null);
  protected readonly cohortMonths = signal(6);

  // --- Forecast (§6.5) ----------------------------------------------------
  protected readonly forecast = signal<ForecastReport | null>(null);
  protected readonly forecastLoading = signal(false);
  protected readonly forecastError = signal<string | null>(null);

  ngOnInit(): void {
    this.loadTargets();
  }

  selectTab(tab: AnalyticsTab): void {
    this.activeTab.set(tab);
    if (tab === 'retention' && !this.retention()) {
      this.loadRetention();
    } else if (tab === 'forecast' && !this.forecast()) {
      this.loadForecast();
    }
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
        this.forecastLoading.set(false);
      },
      error: () => {
        this.forecastError.set('Could not load the forecast.');
        this.forecastLoading.set(false);
      },
    });
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
    const n = Number(value ?? 0);
    return '₹' + Math.round(n).toLocaleString('en-IN');
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
