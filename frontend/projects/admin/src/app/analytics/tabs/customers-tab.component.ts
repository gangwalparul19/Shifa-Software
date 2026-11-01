import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexGrid,
  ApexLegend,
  ApexPlotOptions,
  ApexStroke,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { formatDateLabel } from '../../shared/date-label.util';
import { formatInr } from '../../shared/inr.pipe';
import { readPageSize, writePageSize } from '../../shared/page-size.util';
import { PaginationComponent } from '../../shared/pagination.component';
import { whatsAppHref, whatsAppMessage } from '../../shared/whatsapp.util';
import { BandRow, CustomerRow, CustomerSegmentsReport, SegmentKey, SegmentRow } from '../analytics.model';

// --- Look and feel shared with the rest of the Analytics page -----------------

const BRAND = '#1f5d3f';
const BLUE = '#0284c7';
const TEXT = '#6b7c74';
const GRID = 'rgba(15,51,36,0.08)';

/** Charts stay still for people who ask their system for less motion. */
const REDUCED_MOTION =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;

interface SegmentStyle {
  color: string;
  soft: string;
  icon: string;
}

/** One colour per engagement segment, used on its card and in the share chart. */
const SEGMENT_STYLE: Record<SegmentKey, SegmentStyle> = {
  CHAMPION: { color: '#1f5d3f', soft: '#e6f2ec', icon: 'ti-trophy' },
  LOYAL: { color: '#0d9488', soft: '#e0f5f2', icon: 'ti-heart' },
  NEW: { color: '#0284c7', soft: '#e0f2fe', icon: 'ti-sparkles' },
  NEEDS_ATTENTION: { color: '#d97706', soft: '#fdf1de', icon: 'ti-bell-ringing' },
  AT_RISK: { color: '#ea580c', soft: '#ffece0', icon: 'ti-alert-triangle' },
  ONE_TIME_LAPSED: { color: '#6b7280', soft: '#f1f3f2', icon: 'ti-user-off' },
  HIBERNATING: { color: '#991b1b', soft: '#f6e7e7', icon: 'ti-moon' },
};

const FALLBACK_STYLE: SegmentStyle = { color: '#6b7c74', soft: '#eef1ef', icon: 'ti-users' };

// --- View models --------------------------------------------------------------

interface CustomerView {
  c: CustomerRow;
  tel: string | null;
  wa: string | null;
  revenueText: string;
  lastOrderText: string;
  agoText: string;
}

interface SegmentView {
  key: SegmentKey;
  row: SegmentRow;
  color: string;
  soft: string;
  icon: string;
  customersText: string;
  customerShare: string;
  revenueText: string;
  revenueShare: string;
  avgOrders: string;
  recency: string;
  people: CustomerView[];
}

interface KpiTile {
  key: string;
  label: string;
  icon: string;
  value: string;
  sub: string;
}

interface BandView {
  label: string;
  customers: string;
  revenue: string;
}

interface CopyState {
  key: string;
  ok: boolean;
  count: number;
}

/** ApexCharts options of the two stacked share bars (customers and revenue by segment). */
interface ShareChartOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  plotOptions: ApexPlotOptions;
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis;
  legend: ApexLegend;
  tooltip: ApexTooltip;
  grid: ApexGrid;
}

/** ApexCharts options of the single-series column charts. */
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

// --- Pure helpers -------------------------------------------------------------

function whole(value: number): string {
  return Number.isFinite(value) ? Math.round(value).toLocaleString('en-IN') : '0';
}

function pctText(value: number): string {
  return Number.isFinite(value) ? `${Math.round(value * 10) / 10}%` : '—';
}

function oneDecimal(value: number): string {
  return Number.isFinite(value) ? String(Math.round(value * 10) / 10) : '—';
}

function agoText(days: number): string {
  if (!Number.isFinite(days) || days < 0) {
    return '—';
  }
  if (days === 0) {
    return 'today';
  }
  return `${whole(days)} ${days === 1 ? 'day' : 'days'} ago`;
}

function customerView(c: CustomerRow): CustomerView {
  const digits = (c.mobile ?? '').replace(/[^\d+]/g, '');
  return {
    c,
    tel: digits ? `tel:${digits}` : null,
    wa: whatsAppHref(c.mobile, whatsAppMessage('followup', { customerName: c.name })),
    revenueText: formatInr(c.revenue, 0),
    lastOrderText: c.lastOrderDate ? formatDateLabel(c.lastOrderDate) : '—',
    agoText: agoText(c.daysSinceLastOrder),
  };
}

function styleOf(key: SegmentKey): SegmentStyle {
  return SEGMENT_STYLE[key] ?? FALLBACK_STYLE;
}

function bandViews(rows: BandRow[]): BandView[] {
  return rows.map((row) => ({
    label: row.label,
    customers: whole(row.customers),
    revenue: formatInr(row.revenue, 0),
  }));
}

function barChart(name: string, rows: BandRow[], color: string): BarOptions {
  return {
    series: [{ name, data: rows.map((row) => row.customers) }],
    chart: {
      type: 'bar',
      height: 280,
      fontFamily: 'inherit',
      toolbar: { show: false },
      parentHeightOffset: 0,
      animations: { enabled: !REDUCED_MOTION },
    },
    colors: [color],
    dataLabels: { enabled: true, offsetY: -18, style: { fontSize: '11px', colors: ['#3d4f46'] } },
    plotOptions: { bar: { borderRadius: 4, columnWidth: '55%', dataLabels: { position: 'top' } } },
    xaxis: {
      categories: rows.map((row) => row.label),
      labels: { rotate: -30, hideOverlappingLabels: true, trim: true, style: { colors: TEXT } },
      axisBorder: { show: false },
      axisTicks: { show: false },
    },
    yaxis: { labels: { formatter: (v: number) => String(Math.round(v)), style: { colors: TEXT } } },
    grid: { borderColor: GRID, strokeDashArray: 4 },
    tooltip: { theme: 'light', y: { formatter: (v: number) => `${Math.round(v)} customers` } },
  };
}

/** Copies text with the old textarea trick, for browsers or pages without the async clipboard API. */
function fallbackCopy(text: string): boolean {
  if (typeof document === 'undefined') {
    return false;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.top = '0';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try {
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    document.body.removeChild(area);
  }
}

/**
 * The Customers tab of the enhanced Analytics page: headline customer numbers, the engagement
 * segments with ready-made call lists, how customers and revenue split by segment, how often and
 * how recently customers order, and the top customers. Purely presentational: the parent loads
 * the data and switches to the Data tab when {@link openDatasets} fires.
 */
@Component({
  selector: 'admin-analytics-customers',
  standalone: true,
  imports: [NgApexchartsModule, PaginationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customers-tab.component.html',
  styleUrl: './customers-tab.component.css',
})
export class CustomersTabComponent {
  private readonly destroyRef = inject(DestroyRef);

  /** The customer segments report. */
  readonly report = input.required<CustomerSegmentsReport>();

  /** The user wants the full customer list: the parent shows the Data tab. */
  readonly openDatasets = output<void>();

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.copyTimer !== null) {
        clearTimeout(this.copyTimer);
      }
    });
  }

  // --- Headline numbers -------------------------------------------------------

  protected readonly tiles = computed<KpiTile[]>(() => {
    const r = this.report();
    return [
      {
        key: 'customers',
        label: 'Customers',
        icon: 'ti-users',
        value: whole(r.totalCustomers),
        sub: `Counted up to ${formatDateLabel(r.asOf)}`,
      },
      {
        key: 'repeat-rate',
        label: 'Repeat rate',
        icon: 'ti-repeat',
        value: pctText(r.repeatRatePct),
        sub: 'of customers ordered more than once',
      },
      {
        key: 'lifetime-value',
        label: 'Average lifetime value',
        icon: 'ti-coin-rupee',
        value: formatInr(r.avgLifetimeValue, 0),
        sub: 'spent per customer so far',
      },
      {
        key: 'top-share',
        label: 'Top 10% of customers bring',
        icon: 'ti-chart-pie',
        value: pctText(r.top10PctRevenueSharePct),
        sub: `of revenue. The top 20% bring ${pctText(r.top20PctRevenueSharePct)}`,
      },
      {
        key: 'gone-quiet',
        label: 'Repeat customers gone quiet',
        icon: 'ti-user-exclamation',
        value: whole(r.atRiskCustomers),
        sub: `${formatInr(r.atRiskRevenue, 0)} lifetime value, no order for 61+ days`,
      },
    ];
  });

  // --- Engagement segments ----------------------------------------------------

  protected readonly segmentViews = computed<SegmentView[]>(() =>
    this.report().segments.map((row) => {
      const style = styleOf(row.key);
      return {
        key: row.key,
        row,
        color: style.color,
        soft: style.soft,
        icon: style.icon,
        customersText: whole(row.customers),
        customerShare: pctText(row.customerSharePct),
        revenueText: formatInr(row.revenue, 0),
        revenueShare: pctText(row.revenueSharePct),
        avgOrders: oneDecimal(row.avgOrders),
        recency: whole(row.avgRecencyDays),
        people: (row.topCustomers ?? []).map(customerView),
      };
    }),
  );

  /** The segment whose customer list is open (only one at a time). */
  protected readonly openKey = signal<SegmentKey | null>(null);

  protected toggle(key: SegmentKey): void {
    this.openKey.update((current) => (current === key ? null : key));
  }

  // --- Copy mobiles -----------------------------------------------------------

  protected readonly copyState = signal<CopyState | null>(null);
  private copyTimer: ReturnType<typeof setTimeout> | null = null;

  /** Copies the listed customers' mobile numbers, one per line, and shows a short inline note. */
  protected async copyMobiles(key: string, people: CustomerView[]): Promise<void> {
    const numbers = people.map((p) => (p.c.mobile ?? '').trim()).filter((m) => m.length > 0);
    if (numbers.length === 0) {
      return;
    }
    const text = numbers.join('\n');
    let ok = false;
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
        await navigator.clipboard.writeText(text);
        ok = true;
      }
    } catch {
      ok = false;
    }
    if (!ok) {
      ok = fallbackCopy(text);
    }
    this.copyState.set({ key, ok, count: numbers.length });
    if (this.copyTimer !== null) {
      clearTimeout(this.copyTimer);
    }
    this.copyTimer = setTimeout(() => {
      this.copyState.set(null);
      this.copyTimer = null;
    }, 3500);
  }

  // --- Charts -----------------------------------------------------------------

  /** Customers and revenue as two bars, each split by segment (shares add up to 100%). */
  protected readonly shareChart = computed<ShareChartOptions | null>(() => {
    const segments = this.report().segments;
    if (segments.length === 0 || this.report().totalCustomers <= 0) {
      return null;
    }
    return {
      series: segments.map((s) => ({
        name: s.label,
        data: [Math.round(s.customerSharePct * 10) / 10, Math.round(s.revenueSharePct * 10) / 10],
      })),
      chart: {
        type: 'bar',
        height: 240,
        stacked: true,
        stackType: '100%',
        fontFamily: 'inherit',
        toolbar: { show: false },
        parentHeightOffset: 0,
        animations: { enabled: !REDUCED_MOTION },
      },
      colors: segments.map((s) => styleOf(s.key).color),
      plotOptions: { bar: { horizontal: true, barHeight: '58%', borderRadius: 3 } },
      dataLabels: {
        enabled: true,
        formatter: (v: number) => (v >= 6 ? `${Math.round(v)}%` : ''),
        style: { fontSize: '11px', colors: ['#ffffff'] },
      },
      stroke: { width: 1, colors: ['#ffffff'] },
      xaxis: {
        categories: ['Customers', 'Revenue'],
        labels: { style: { colors: TEXT } },
        axisBorder: { show: false },
        axisTicks: { show: false },
      },
      yaxis: { labels: { style: { colors: TEXT } } },
      legend: { position: 'bottom', horizontalAlign: 'left', fontFamily: 'inherit' },
      tooltip: { theme: 'light', y: { formatter: (v: number) => `${Math.round(v * 10) / 10}%` } },
      grid: { borderColor: GRID, strokeDashArray: 4 },
    };
  });

  protected readonly shareAria = computed(() => {
    const parts = this.segmentViews().map(
      (s) => `${s.row.label}: ${s.customerShare} of customers, ${s.revenueShare} of revenue`,
    );
    return `Chart of customers and revenue by segment. ${parts.join('. ')}. The same numbers are in the table below.`;
  });

  protected readonly frequencyRows = computed<BandView[]>(() => bandViews(this.report().orderFrequency));
  protected readonly recencyRows = computed<BandView[]>(() => bandViews(this.report().recencyBands));

  protected readonly frequencyChart = computed<BarOptions | null>(() => {
    const rows = this.report().orderFrequency;
    return rows.length > 0 ? barChart('Customers', rows, BRAND) : null;
  });

  protected readonly recencyChart = computed<BarOptions | null>(() => {
    const rows = this.report().recencyBands;
    return rows.length > 0 ? barChart('Customers', rows, BLUE) : null;
  });

  protected readonly frequencyAria = computed(
    () =>
      `Chart of customers by number of orders placed. ${this.frequencyRows()
        .map((r) => `${r.label}: ${r.customers} customers`)
        .join('. ')}. The same numbers are in the table below.`,
  );

  protected readonly recencyAria = computed(
    () =>
      `Chart of customers by time since their last order. ${this.recencyRows()
        .map((r) => `${r.label}: ${r.customers} customers`)
        .join('. ')}. The same numbers are in the table below.`,
  );

  // --- Top customers (paged on the client) ------------------------------------

  private readonly topViews = computed<CustomerView[]>(() => this.report().topCustomers.map(customerView));

  protected readonly topPage = signal(0);
  protected readonly topSize = signal(readPageSize('analyticsTopCustomers', 10));
  protected readonly topTotal = computed(() => this.topViews().length);
  protected readonly topTotalPages = computed(() => Math.max(1, Math.ceil(this.topTotal() / this.topSize())));
  protected readonly topCurrentPage = computed(() => Math.min(this.topPage(), this.topTotalPages() - 1));
  protected readonly topRows = computed<CustomerView[]>(() => {
    const start = this.topCurrentPage() * this.topSize();
    return this.topViews().slice(start, start + this.topSize());
  });

  protected setTopPage(page: number): void {
    this.topPage.set(Math.max(0, page));
  }

  protected setTopSize(size: number): void {
    this.topSize.set(size);
    writePageSize('analyticsTopCustomers', size);
    this.topPage.set(0);
  }
}
