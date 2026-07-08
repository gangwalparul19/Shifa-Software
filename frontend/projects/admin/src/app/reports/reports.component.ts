import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexFill,
  ApexGrid,
  ApexPlotOptions,
  ApexStroke,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { DownloadResult, ReportsService } from './reports.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import {
  DatePreset,
  ExportFormat,
  ReportResponse,
  ReportType,
  ReportTypeOption,
  VyaparFormat,
} from './reports.model';

interface Toast {
  kind: 'ok' | 'error';
  text: string;
}

/** The four presentation tabs shown in the mockup, mapped onto our report types. */
type ReportTab = 'overview' | 'sales' | 'products' | 'customers';

/** ApexCharts option bundle for the revenue-trend bar chart. */
interface RevenueBarOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  plotOptions: ApexPlotOptions;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis;
  fill: ApexFill;
  grid: ApexGrid;
  tooltip: ApexTooltip;
}

/** ApexCharts option bundle for the compact revenue sparkline (area). */
interface SparkOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  stroke: ApexStroke;
  fill: ApexFill;
  tooltip: ApexTooltip;
}

/**
 * Reporting and export view (Req 20.1, 20.2, 20.4, 23.1; mobile redesign Req 12).
 *
 * <p>Presents the report data behind the client-mockup layout: presentation
 * tabs (Overview / Sales / Products / Customers), a date-range chip, a headline
 * Total Revenue card with a sparkline, a row of KPI tiles, and a revenue-trend
 * bar chart &mdash; all driven by the existing {@code GET /api/reports/&#123;type&#125;}
 * endpoint and its summary + table payload (no new backend calls). The report
 * type &lt;select&gt;, custom range, and Excel/PDF/Vyapar exports are retained so
 * every prior capability (including state- and salesperson-wise reports) stays
 * reachable. The route is guarded for ADMIN + ACCOUNTANT; the backend also
 * scopes a salesperson to their own orders if reached directly.
 */
@Component({
  selector: 'admin-reports',
  imports: [ReactiveFormsModule, PageHeaderComponent, NgApexchartsModule],
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.css',
})
export class ReportsComponent implements OnInit {
  private readonly service = inject(ReportsService);
  private readonly fb = inject(FormBuilder);

  /** Honour reduced-motion by disabling chart animations. */
  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  protected readonly reportTypes: ReportTypeOption[] = [
    { value: 'daily', label: 'Daily' },
    { value: 'monthly', label: 'Monthly' },
    { value: 'product', label: 'Product-wise' },
    { value: 'state', label: 'State-wise' },
    { value: 'salesperson', label: 'Salesperson-wise' },
  ];

  /** The mockup's presentation tabs and the report type each maps onto. */
  protected readonly tabs: { key: ReportTab; label: string; icon: string }[] = [
    { key: 'overview', label: 'Overview', icon: 'ti-layout-dashboard' },
    { key: 'sales', label: 'Sales', icon: 'ti-chart-line' },
    { key: 'products', label: 'Products', icon: 'ti-leaf' },
    { key: 'customers', label: 'Customers', icon: 'ti-users' },
  ];

  /** The active presentation tab; drives the report type (except Customers). */
  protected readonly activeTab = signal<ReportTab>('overview');

  protected readonly presets: DatePreset[] = [
    { key: 'today', label: 'Today' },
    { key: 'last7', label: 'Last 7 Days' },
    { key: 'last30', label: 'Last 30 Days' },
    { key: 'month', label: 'This Month' },
    { key: 'custom', label: 'Custom' },
  ];

  protected readonly form = this.fb.nonNullable.group({
    type: 'daily' as ReportType,
    preset: 'last30',
    from: '',
    to: '',
  });

  protected readonly report = signal<ReportResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly toast = signal<Toast | null>(null);

  private toastTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.applyPreset('last30');
    this.generate();
  }

  /** Whether the custom from/to inputs are active. */
  protected isCustom(): boolean {
    return this.form.controls.preset.value === 'custom';
  }

  /** The human label for the active date range (for the range chip). */
  protected rangeLabel(): string {
    const preset = this.presets.find((p) => p.key === this.form.controls.preset.value);
    if (preset && preset.key !== 'custom') {
      return preset.label;
    }
    const from = this.form.controls.from.value;
    const to = this.form.controls.to.value;
    return from && to ? `${from} → ${to}` : 'Custom range';
  }

  onPresetChange(key: string): void {
    this.form.controls.preset.setValue(key);
    if (key !== 'custom') {
      this.applyPreset(key);
      this.generate();
    }
  }

  // --- Presentation tabs --------------------------------------------------

  /** Maps a presentation tab onto its backing report type (Customers has none). */
  private tabType(tab: ReportTab): ReportType | null {
    switch (tab) {
      case 'sales':
        return 'monthly';
      case 'products':
        return 'product';
      case 'customers':
        return null;
      default:
        return 'daily';
    }
  }

  /** Switches the active tab; wires the ones we have data for and regenerates. */
  selectTab(tab: ReportTab): void {
    this.activeTab.set(tab);
    const type = this.tabType(tab);
    if (type) {
      this.form.controls.type.setValue(type);
      this.generate();
    }
  }

  /** Whether the active tab has no backing report data (graceful "coming soon"). */
  protected isComingSoon(): boolean {
    return this.tabType(this.activeTab()) === null;
  }

  /** The revenue-trend granularity, reflected in the chart's period dropdown. */
  protected trendGranularity(): 'daily' | 'monthly' {
    return this.form.controls.type.value === 'monthly' ? 'monthly' : 'daily';
  }

  /** Changes the revenue-trend granularity via the period dropdown (Daily/Monthly). */
  setTrendGranularity(value: string): void {
    const type: ReportType = value === 'monthly' ? 'monthly' : 'daily';
    this.form.controls.type.setValue(type);
    this.activeTab.set(type === 'monthly' ? 'sales' : 'overview');
    this.generate();
  }

  /** Resolves a preset key into concrete from/to ISO dates in the form. */
  private applyPreset(key: string): void {
    const today = new Date();
    const iso = (d: Date) => d.toISOString().slice(0, 10);
    let from = '';
    let to = iso(today);
    switch (key) {
      case 'today':
        from = iso(today);
        break;
      case 'last7': {
        const d = new Date(today);
        d.setDate(d.getDate() - 6);
        from = iso(d);
        break;
      }
      case 'last30': {
        const d = new Date(today);
        d.setDate(d.getDate() - 29);
        from = iso(d);
        break;
      }
      case 'month': {
        from = iso(new Date(today.getFullYear(), today.getMonth(), 1));
        break;
      }
      default:
        return;
    }
    this.form.controls.from.setValue(from);
    this.form.controls.to.setValue(to);
  }

  private currentRange(): { from: string | null; to: string | null } {
    const from = this.form.controls.from.value || null;
    const to = this.form.controls.to.value || null;
    return { from, to };
  }

  generate(): void {
    if (this.isComingSoon()) {
      return;
    }
    const type = this.form.controls.type.value;
    const { from, to } = this.currentRange();
    this.loading.set(true);
    this.loadError.set(null);
    this.service.report(type, from, to).subscribe({
      next: (r) => {
        this.report.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set('Could not generate the report. Please try again.');
      },
    });
  }

  // --- Derived KPI values -------------------------------------------------

  /** The report's total sales as a number (0 when unavailable). */
  protected totalSalesValue(): number {
    return this.toNumber(this.report()?.summary.totalSales);
  }

  /** Average order value = total sales / order count (0 when no orders). */
  protected avgOrderValue(): number {
    const r = this.report();
    if (!r || !r.summary.orderCount) {
      return 0;
    }
    return this.totalSalesValue() / r.summary.orderCount;
  }

  /** The sign of the sales-vs-previous change, for colour/arrow styling. */
  protected changeDirection(): 'up' | 'down' | 'flat' {
    const s = this.report()?.summary;
    if (!s || !s.salesChangeApplicable || s.salesChangePercent === null) {
      return 'flat';
    }
    const pct = Number(s.salesChangePercent);
    if (!Number.isFinite(pct) || pct === 0) {
      return 'flat';
    }
    return pct > 0 ? 'up' : 'down';
  }

  /** The sales-vs-previous change with sign, or an em dash when not applicable. */
  protected changeText(): string {
    const s = this.report()?.summary;
    if (!s || !s.salesChangeApplicable || s.salesChangePercent === null) {
      return '—';
    }
    const pct = Number(s.salesChangePercent);
    const sign = pct > 0 ? '+' : '';
    return `${sign}${s.salesChangePercent}%`;
  }

  // --- Revenue-trend chart data (derived from the report table, no new API) --

  /**
   * Extracts a label/value time-series from the report table by finding the
   * first sales/revenue/amount/total column (falling back to the last column).
   * Returns null when no numeric series can be derived (graceful empty state).
   */
  private trendData(): { labels: string[]; values: number[] } | null {
    const r = this.report();
    if (!r || r.rows.length === 0) {
      return null;
    }
    const matchIdx = r.headers.findIndex((h) => /sales|revenue|amount|total/i.test(h));
    const valueIdx = matchIdx >= 0 ? matchIdx : r.headers.length - 1;
    const labels: string[] = [];
    const values: number[] = [];
    for (const row of r.rows) {
      const v = this.toNumber(row[valueIdx]);
      if (!Number.isFinite(v)) {
        continue;
      }
      labels.push((row[0] ?? '').toString());
      values.push(v);
    }
    return values.length > 0 ? { labels, values } : null;
  }

  /** The revenue-trend bar chart, or null when no series can be derived. */
  protected readonly revenueBarChart = computed<RevenueBarOptions | null>(() => {
    // Read the report signal so this recomputes when a new report loads.
    const data = this.report() ? this.trendData() : null;
    if (!data) {
      return null;
    }
    return {
      series: [{ name: 'Revenue', data: data.values.map((v) => Math.round(v)) }],
      chart: {
        type: 'bar',
        height: 280,
        fontFamily: 'inherit',
        toolbar: { show: false },
        animations: { enabled: !this.reducedMotion },
      },
      colors: ['#1f5d3f'],
      dataLabels: { enabled: false },
      plotOptions: { bar: { borderRadius: 6, columnWidth: '55%' } },
      xaxis: {
        categories: data.labels.map((l) => this.shortLabel(l)),
        labels: { rotate: -45, hideOverlappingLabels: true, style: { colors: '#6b7c74' } },
        axisBorder: { show: false },
        axisTicks: { show: false },
      },
      yaxis: { labels: { formatter: (v: number) => this.compact(v), style: { colors: '#6b7c74' } } },
      fill: {
        type: 'gradient',
        gradient: { shade: 'dark', type: 'vertical', shadeIntensity: 0.15, opacityFrom: 0.95, opacityTo: 0.75 },
      },
      grid: { borderColor: 'rgba(15,51,36,0.08)', strokeDashArray: 4 },
      tooltip: { theme: 'light', y: { formatter: (v: number) => this.inr(v) } },
    };
  });

  /** A compact area sparkline for the Total Revenue card, or null when empty. */
  protected readonly revenueSpark = computed<SparkOptions | null>(() => {
    const data = this.report() ? this.trendData() : null;
    if (!data || data.values.length < 2) {
      return null;
    }
    return {
      series: [{ name: 'Revenue', data: data.values.map((v) => Math.round(v)) }],
      chart: {
        type: 'area',
        height: 70,
        sparkline: { enabled: true },
        fontFamily: 'inherit',
        animations: { enabled: !this.reducedMotion },
      },
      colors: ['#1f5d3f'],
      stroke: { curve: 'smooth', width: 2 },
      fill: {
        type: 'gradient',
        gradient: { shadeIntensity: 1, opacityFrom: 0.35, opacityTo: 0.05, stops: [0, 90, 100] },
      },
      tooltip: { theme: 'light', y: { formatter: (v: number) => this.inr(v) } },
    };
  });

  exportFile(format: ExportFormat): void {
    const type = this.form.controls.type.value;
    const { from, to } = this.currentRange();
    this.exporting.set(true);
    this.service.export(type, format, from, to).subscribe({
      next: (result) => {
        this.exporting.set(false);
        this.saveFile(result);
        this.showToast('ok', `Exported ${format.toUpperCase()} file.`);
      },
      error: () => {
        this.exporting.set(false);
        this.showToast('error', 'Export failed. Please try again.');
      },
    });
  }

  exportVyapar(format: VyaparFormat): void {
    const { from, to } = this.currentRange();
    this.exporting.set(true);
    this.service.vyapar(format, from, to).subscribe({
      next: (result) => {
        this.exporting.set(false);
        this.saveFile(result);
        if (result.message) {
          this.showToast('error', result.message);
        } else {
          this.showToast('ok', `Exported Vyapar ${format.toUpperCase()} file.`);
        }
      },
      error: () => {
        this.exporting.set(false);
        this.showToast('error', 'Vyapar export failed. Please try again.');
      },
    });
  }

  /** Triggers a browser download of the exported file blob. */
  private saveFile(result: DownloadResult): void {
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }

  private showToast(kind: Toast['kind'], text: string): void {
    this.toast.set({ kind, text });
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), 5000);
  }

  // --- Formatting helpers -------------------------------------------------

  /** Parses a possibly-formatted numeric string (strips ₹, commas, spaces). */
  private toNumber(value: string | null | undefined): number {
    if (value === null || value === undefined) {
      return 0;
    }
    const cleaned = value.toString().replace(/[₹,\s]/g, '');
    const n = Number(cleaned);
    return Number.isFinite(n) ? n : 0;
  }

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

  /** Trims an ISO date bucket label to something compact (yyyy-MM-dd → MM-dd). */
  private shortLabel(label: string): string {
    const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(label);
    if (iso) {
      return `${iso[2]}-${iso[3]}`;
    }
    return label;
  }
}
