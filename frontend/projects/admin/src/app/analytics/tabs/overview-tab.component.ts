import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ApexAxisChartSeries,
  ApexChart,
  ApexDataLabels,
  ApexGrid,
  ApexLegend,
  ApexMarkers,
  ApexPlotOptions,
  ApexStroke,
  ApexTooltip,
  ApexXAxis,
  ApexYAxis,
  NgApexchartsModule,
} from 'ng-apexcharts';
import { formatDateLabel } from '../../shared/date-label.util';
import { formatCompactInr, formatInr } from '../../shared/inr.pipe';
import { StatePanelComponent } from '../../shared/state-panel.component';
import {
  GeoRow,
  GrowthKpis,
  MixRow,
  OverviewReport,
  Pointer,
  PointerSeverity,
  PointersReport,
  TrendPoint,
} from '../analytics.model';
import {
  WEEKDAYS_LONG,
  WEEKDAYS_SHORT,
  changeIcon,
  changeLabel,
  changePct,
  changeTone,
  hourLabel,
  splitRoute,
} from '../analytics-format.util';

// --- Look and feel shared with the rest of the Analytics page -----------------

const BRAND = '#1f5d3f';
const BLUE = '#0284c7';
const AMBER = '#d97706';
const TEXT = '#6b7c74';
const GRID = 'rgba(15,51,36,0.08)';
const PALETTE = [BRAND, BLUE, AMBER, '#7c3aed', '#dc2626', '#6b7c74'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const HOURS: number[] = Array.from({ length: 24 }, (_, hour) => hour);

/** Charts stay still for people who ask their system for less motion. */
const REDUCED_MOTION =
  typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;

// --- View models --------------------------------------------------------------

interface SeverityStyle {
  severity: PointerSeverity;
  label: string;
  hint: string;
  icon: string;
  cls: string;
}

/** Plain-language meaning, icon and colour class of each pointer severity. */
const SEVERITY: Record<PointerSeverity, SeverityStyle> = {
  WARNING: {
    severity: 'WARNING',
    label: 'Needs attention',
    hint: 'Something is slipping and is worth fixing soon.',
    icon: 'ti-alert-triangle',
    cls: 'ptr--warning',
  },
  OPPORTUNITY: {
    severity: 'OPPORTUNITY',
    label: 'Growth chance',
    hint: 'A way to sell more or keep more customers.',
    icon: 'ti-bulb',
    cls: 'ptr--opportunity',
  },
  POSITIVE: {
    severity: 'POSITIVE',
    label: 'Good news',
    hint: 'Something is going well.',
    icon: 'ti-circle-check',
    cls: 'ptr--positive',
  },
  INFO: {
    severity: 'INFO',
    label: 'Worth knowing',
    hint: 'For your information, no action needed.',
    icon: 'ti-info-circle',
    cls: 'ptr--info',
  },
};

const SEVERITY_LEGEND: SeverityStyle[] = [SEVERITY.WARNING, SEVERITY.OPPORTUNITY, SEVERITY.POSITIVE, SEVERITY.INFO];

interface PointerView {
  pointer: Pointer;
  label: string;
  icon: string;
  cls: string;
  link: { path: string; query: Record<string, string> } | null;
}

interface KpiTile {
  key: string;
  label: string;
  icon: string;
  value: string;
  sub: string | null;
  changeText: string;
  tone: string;
  arrow: string;
  previousText: string;
}

interface TrendSummary {
  totalRevenue: string;
  totalOrders: string;
  bestLabel: string;
  bestRevenue: string;
  bestOrders: string;
  averageRevenue: string;
}

interface TrendRowView {
  label: string;
  orders: string;
  revenue: string;
  newCustomers: string;
}

interface MixRowView {
  label: string;
  orders: string;
  revenue: string;
  share: string;
  barWidth: number;
  aov: string;
  success: string;
  successTone: string;
  color: string;
}

interface DonutOptions {
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

interface MixCard {
  key: string;
  title: string;
  subtitle: string;
  icon: string;
  firstColumn: string;
  rows: MixRowView[];
  totalOrders: string;
  totalRevenue: string;
  donut: DonutOptions | null;
  donutAria: string;
}

interface GeoRowView {
  name: string;
  orders: string;
  revenue: string;
  share: string;
  barWidth: number;
  customers: string;
  aov: string;
}

interface GeoCard {
  key: string;
  title: string;
  subtitle: string;
  firstColumn: string;
  rows: GeoRowView[];
}

interface HeatCell {
  count: number;
  bg: string;
  fg: string;
  label: string;
}

interface HeatRow {
  shortDay: string;
  longDay: string;
  total: number;
  cells: HeatCell[];
}

interface WeekdayBar {
  day: string;
  count: number;
  sharePct: string;
  width: number;
  isPeak: boolean;
}

interface HeatView {
  hasData: boolean;
  rows: HeatRow[];
  peakDay: string | null;
  peakHour: string | null;
  bars: WeekdayBar[];
  totalOrders: number;
}

/** ApexCharts options of the sales-trend (columns + line on two axes). */
interface TrendChartOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  plotOptions: ApexPlotOptions;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis[];
  legend: ApexLegend;
  tooltip: ApexTooltip;
  grid: ApexGrid;
  markers: ApexMarkers;
}

// --- Pure helpers -------------------------------------------------------------

function whole(value: number): string {
  return Number.isFinite(value) ? Math.round(value).toLocaleString('en-IN') : '0';
}

function pctText(value: number): string {
  return Number.isFinite(value) ? `${Math.round(value * 10) / 10}%` : '—';
}

function clampPct(value: number): number {
  return Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : 0;
}

/** "05 Oct" from a yyyy-MM-dd string, without going through any time zone. */
function shortDay(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) {
    return iso;
  }
  return `${match[3]} ${MONTHS[Number(match[2]) - 1] ?? match[2]}`;
}

function successTone(rate: number | null): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) {
    return 'text-secondary';
  }
  if (rate >= 80) {
    return 'text-success';
  }
  if (rate >= 50) {
    return 'text-warning';
  }
  return 'text-danger';
}

/** Change in percentage points, or null when the earlier period has no orders to compare with. */
function pointsChange(current: number, previous: number, comparable: boolean): number | null {
  if (!comparable || !Number.isFinite(current) || !Number.isFinite(previous)) {
    return null;
  }
  return Math.round((current - previous) * 10) / 10;
}

function pointsLabel(change: number | null): string {
  if (change === null) {
    return '—';
  }
  return `${change > 0 ? '+' : ''}${change.toFixed(1)} pts`;
}

function tile(
  key: string,
  label: string,
  icon: string,
  value: string,
  previousText: string,
  change: number | null,
  higherIsBetter: boolean,
  sub: string | null,
  unit: 'pct' | 'pts' = 'pct',
): KpiTile {
  return {
    key,
    label,
    icon,
    value,
    sub,
    changeText: unit === 'pts' ? pointsLabel(change) : changeLabel(change),
    tone: changeTone(change, higherIsBetter),
    arrow: changeIcon(change),
    previousText,
  };
}

function buildTiles(c: GrowthKpis, p: GrowthKpis): KpiTile[] {
  const money = (v: number): string => formatInr(v, 0);
  const sharesComparable = p.orders > 0;
  return [
    tile('revenue', 'Revenue', 'ti-currency-rupee', money(c.revenue), money(p.revenue), changePct(c.revenue, p.revenue), true, null),
    tile('orders', 'Orders', 'ti-shopping-cart', whole(c.orders), whole(p.orders), changePct(c.orders, p.orders), true, null),
    tile(
      'aov',
      'Average order value',
      'ti-receipt',
      formatInr(c.avgOrderValue, 2),
      formatInr(p.avgOrderValue, 2),
      changePct(c.avgOrderValue, p.avgOrderValue),
      true,
      null,
    ),
    tile('customers', 'Customers', 'ti-users', whole(c.customers), whole(p.customers), changePct(c.customers, p.customers), true, null),
    tile(
      'new-customers',
      'New customers',
      'ti-user-plus',
      whole(c.newCustomers),
      whole(p.newCustomers),
      changePct(c.newCustomers, p.newCustomers),
      true,
      null,
    ),
    tile(
      'returning-share',
      "Returning customers' share of revenue",
      'ti-repeat',
      pctText(c.returningRevenueSharePct),
      pctText(p.returningRevenueSharePct),
      pointsChange(c.returningRevenueSharePct, p.returningRevenueSharePct, sharesComparable),
      true,
      `${whole(c.returningCustomers)} returning ${c.returningCustomers === 1 ? 'customer' : 'customers'}`,
      'pts',
    ),
    tile(
      'lost-orders',
      'Lost orders',
      'ti-circle-x',
      whole(c.lostOrders),
      whole(p.lostOrders),
      changePct(c.lostOrders, p.lostOrders),
      false,
      `${pctText(c.lostOrdersPct)} of orders placed`,
    ),
    tile(
      'discount',
      'Discount given',
      'ti-discount-2',
      money(c.discountTotal),
      money(p.discountTotal),
      changePct(c.discountTotal, p.discountTotal),
      false,
      `${pctText(c.discountedOrdersPct)} of orders`,
    ),
  ];
}

function donutChart(labels: string[], values: number[], total: number): DonutOptions {
  return {
    series: values,
    labels,
    colors: labels.map((_, i) => PALETTE[i % PALETTE.length]),
    chart: { type: 'donut', height: 260, fontFamily: 'inherit', animations: { enabled: !REDUCED_MOTION } },
    legend: { position: 'bottom', fontFamily: 'inherit' },
    dataLabels: { enabled: true, formatter: (v: number) => `${Math.round(v)}%` },
    plotOptions: {
      pie: {
        donut: {
          size: '66%',
          labels: {
            show: true,
            value: { show: true, formatter: (val: string | number) => formatCompactInr(Number(val)) },
            total: { show: true, label: 'Revenue', formatter: () => formatCompactInr(total) },
          },
        },
      },
    },
    stroke: { width: 2, colors: ['#fff'] },
    tooltip: { theme: 'light', y: { formatter: (v: number) => formatInr(v, 0) } },
  };
}

function mixCard(
  key: string,
  title: string,
  subtitle: string,
  firstColumn: string,
  icon: string,
  rows: MixRow[],
  withDonut: boolean,
): MixCard {
  const totalOrders = rows.reduce((sum, r) => sum + r.orders, 0);
  const totalRevenue = rows.reduce((sum, r) => sum + r.revenue, 0);
  const views: MixRowView[] = rows.map((row, index) => ({
    label: row.label || 'Not given',
    orders: whole(row.orders),
    revenue: formatInr(row.revenue, 0),
    share: pctText(row.revenueSharePct),
    barWidth: clampPct(row.revenueSharePct),
    aov: formatInr(row.avgOrderValue, 0),
    success: row.successRatePct === null || row.successRatePct === undefined ? '—' : pctText(row.successRatePct),
    successTone: successTone(row.successRatePct),
    color: PALETTE[index % PALETTE.length],
  }));
  const donut =
    withDonut && rows.length > 0 && totalRevenue > 0
      ? donutChart(
          views.map((v) => v.label),
          rows.map((r) => Math.round(r.revenue)),
          totalRevenue,
        )
      : null;
  const donutAria = `${title} chart: ${views.map((v) => `${v.label} ${v.share} of revenue`).join(', ')}. The same numbers are in the table.`;
  return {
    key,
    title,
    subtitle,
    icon,
    firstColumn,
    rows: views,
    totalOrders: whole(totalOrders),
    totalRevenue: formatInr(totalRevenue, 0),
    donut,
    donutAria,
  };
}

function geoViews(rows: GeoRow[]): GeoRowView[] {
  return rows.map((row) => ({
    name: row.name || 'Not given',
    orders: whole(row.orders),
    revenue: formatInr(row.revenue, 0),
    share: pctText(row.revenueSharePct),
    barWidth: clampPct(row.revenueSharePct),
    customers: whole(row.customers),
    aov: formatInr(row.avgOrderValue, 0),
  }));
}

/**
 * The first tab of the enhanced Analytics page: key pointers, headline numbers against the
 * previous period, the sales trend, how orders split by channel / lead source / payment type,
 * where customers are, and when they order. Purely presentational: the parent loads the data.
 */
@Component({
  selector: 'admin-analytics-overview',
  standalone: true,
  imports: [NgApexchartsModule, RouterLink, StatePanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-tab.component.html',
  styleUrl: './overview-tab.component.css',
})
export class OverviewTabComponent {
  /** The overview report for the selected period. */
  readonly report = input.required<OverviewReport>();
  /** Ranked findings, loaded separately so the page can show the rest while they arrive. */
  readonly pointers = input<PointersReport | null>(null);
  readonly pointersLoading = input(false);
  readonly pointersError = input<string | null>(null);

  /** The user asked to reload the pointers. */
  readonly retryPointers = output<void>();

  // --- Key pointers -----------------------------------------------------------

  protected readonly legend = SEVERITY_LEGEND;

  /** True until the pointers have arrived (or failed), so the card never claims "all good" too early. */
  protected readonly pointersPending = computed(
    () => this.pointersLoading() || (this.pointers() === null && this.pointersError() === null),
  );

  protected readonly pointerViews = computed<PointerView[]>(() => {
    const list = this.pointers()?.pointers ?? [];
    return list.map((pointer) => {
      const style = SEVERITY[pointer.severity] ?? SEVERITY.INFO;
      return {
        pointer,
        label: style.label,
        icon: style.icon,
        cls: style.cls,
        link: splitRoute(pointer.actionRoute),
      };
    });
  });

  protected readonly pointersPeriod = computed<string | null>(() => {
    const p = this.pointers();
    return p ? `Based on ${formatDateLabel(p.from)} to ${formatDateLabel(p.to)}` : null;
  });

  // --- Headline numbers -------------------------------------------------------

  protected readonly tiles = computed<KpiTile[]>(() => {
    const r = this.report();
    return buildTiles(r.current, r.previous);
  });

  protected readonly previousFrom = computed(() => formatDateLabel(this.report().previousFrom));
  protected readonly previousTo = computed(() => formatDateLabel(this.report().previousTo));

  // --- Sales trend ------------------------------------------------------------

  protected readonly trendUnit = computed(() => (this.report().trendBucket === 'WEEK' ? 'week' : 'day'));

  protected readonly trendCaption = computed(
    () => `Revenue (columns, left axis) and orders (line, right axis) per ${this.trendUnit()}`,
  );

  protected readonly trendAria = computed(
    () =>
      `Sales trend chart showing revenue and orders per ${this.trendUnit()}. The same numbers are in the table below the chart.`,
  );

  protected readonly trendHasData = computed(() =>
    this.report().trend.some((point) => point.revenue > 0 || point.orders > 0),
  );

  protected readonly trendSummary = computed<TrendSummary | null>(() => {
    const trend = this.report().trend;
    if (!this.trendHasData()) {
      return null;
    }
    let totalRevenue = 0;
    let totalOrders = 0;
    let best: TrendPoint | null = null;
    for (const point of trend) {
      totalRevenue += point.revenue;
      totalOrders += point.orders;
      if (best === null || point.revenue > best.revenue) {
        best = point;
      }
    }
    const weekly = this.report().trendBucket === 'WEEK';
    const bestLabel = best ? (weekly ? `Week of ${formatDateLabel(best.date)}` : formatDateLabel(best.date)) : '—';
    return {
      totalRevenue: formatInr(totalRevenue, 0),
      totalOrders: whole(totalOrders),
      bestLabel,
      bestRevenue: best ? formatInr(best.revenue, 0) : '—',
      bestOrders: best ? `${whole(best.orders)} ${best.orders === 1 ? 'order' : 'orders'}` : '—',
      averageRevenue: formatInr(trend.length > 0 ? totalRevenue / trend.length : 0, 0),
    };
  });

  protected readonly trendRows = computed<TrendRowView[]>(() => {
    const weekly = this.report().trendBucket === 'WEEK';
    return this.report().trend.map((point) => ({
      label: weekly ? `Week of ${formatDateLabel(point.date)}` : formatDateLabel(point.date),
      orders: whole(point.orders),
      revenue: formatInr(point.revenue, 0),
      newCustomers: whole(point.newCustomers),
    }));
  });

  protected readonly trendChart = computed<TrendChartOptions | null>(() => {
    if (!this.trendHasData()) {
      return null;
    }
    const r = this.report();
    const weekly = r.trendBucket === 'WEEK';
    const labelStyle = { colors: TEXT };
    return {
      series: [
        { name: 'Revenue', type: 'column', data: r.trend.map((p) => Math.round(p.revenue)) },
        { name: 'Orders', type: 'line', data: r.trend.map((p) => p.orders) },
      ],
      chart: {
        type: 'line',
        height: 320,
        fontFamily: 'inherit',
        toolbar: { show: false },
        zoom: { enabled: false },
        parentHeightOffset: 0,
        animations: { enabled: !REDUCED_MOTION },
      },
      colors: [BRAND, AMBER],
      dataLabels: { enabled: false },
      stroke: { width: [0, 3], curve: 'smooth' },
      plotOptions: { bar: { columnWidth: '60%', borderRadius: 3 } },
      markers: { size: [0, 3] },
      xaxis: {
        categories: r.trend.map((p) => shortDay(p.date)),
        title: { text: weekly ? 'Week starting (Monday)' : 'Day', style: { color: TEXT, fontWeight: 400 } },
        labels: { rotate: -45, hideOverlappingLabels: true, style: labelStyle },
        axisBorder: { show: false },
        axisTicks: { show: false },
        tooltip: { enabled: false },
      },
      yaxis: [
        {
          seriesName: 'Revenue',
          min: 0,
          labels: { formatter: (v: number) => formatCompactInr(v), style: labelStyle },
        },
        {
          seriesName: 'Orders',
          opposite: true,
          min: 0,
          forceNiceScale: true,
          labels: { formatter: (v: number) => String(Math.round(v)), style: labelStyle },
        },
      ],
      legend: { position: 'top', horizontalAlign: 'right', fontFamily: 'inherit' },
      tooltip: {
        theme: 'light',
        shared: true,
        intersect: false,
        y: [{ formatter: (v: number) => formatInr(v, 0) }, { formatter: (v: number) => `${Math.round(v)} orders` }],
      },
      grid: { borderColor: GRID, strokeDashArray: 4, padding: { left: 8, right: 8 } },
    };
  });

  // --- Mix cards --------------------------------------------------------------

  protected readonly mixCards = computed<MixCard[]>(() => {
    const r = this.report();
    return [
      mixCard('channel', 'Channel mix', 'Where orders come from', 'Channel', 'ti-building-store', r.channelMix, true),
      mixCard('lead-source', 'Lead source', 'How customers first reached us', 'Lead source', 'ti-compass', r.leadSourceMix, false),
      mixCard('payment', 'Payment type', 'How customers pay', 'Payment type', 'ti-credit-card', r.paymentMix, true),
    ];
  });

  // --- Where customers are ----------------------------------------------------

  protected readonly geoCards = computed<GeoCard[]>(() => {
    const r = this.report();
    return [
      { key: 'states', title: 'Top states', subtitle: 'By revenue', firstColumn: 'State', rows: geoViews(r.topStates) },
      { key: 'cities', title: 'Top cities', subtitle: 'By revenue', firstColumn: 'City', rows: geoViews(r.topCities) },
    ];
  });

  // --- Order timing -----------------------------------------------------------

  protected readonly hourHeaders = HOURS.map((hour) => ({ hour, label: hourLabel(hour) }));
  protected readonly heatScale = [0.14, 0.35, 0.55, 0.78, 1].map((alpha) => `rgba(31, 93, 63, ${alpha})`);

  protected readonly heat = computed<HeatView>(() => {
    const hm = this.report().heatmap;
    const grid = WEEKDAYS_SHORT.map((_, day) =>
      HOURS.map((hour) => {
        const value = Number(hm.cells?.[day]?.[hour] ?? 0);
        return Number.isFinite(value) && value > 0 ? value : 0;
      }),
    );
    let max = Number.isFinite(Number(hm.maxCell)) ? Number(hm.maxCell) : 0;
    let totalOrders = 0;
    for (const row of grid) {
      for (const value of row) {
        totalOrders += value;
        if (value > max) {
          max = value;
        }
      }
    }

    const rows: HeatRow[] = grid.map((counts, day) => ({
      shortDay: WEEKDAYS_SHORT[day],
      longDay: WEEKDAYS_LONG[day],
      total: counts.reduce((sum, value) => sum + value, 0),
      cells: counts.map((count, hour) => {
        const ratio = max > 0 ? count / max : 0;
        return {
          count,
          bg: count === 0 ? '#f1f4f2' : `rgba(31, 93, 63, ${(0.14 + 0.86 * ratio).toFixed(2)})`,
          fg: ratio >= 0.5 ? '#ffffff' : '#173d2a',
          label: `${WEEKDAYS_SHORT[day]} ${hourLabel(hour)} - ${count} ${count === 1 ? 'order' : 'orders'}`,
        };
      }),
    }));

    const maxDay = rows.reduce((m, row) => Math.max(m, row.total), 0);
    const bars: WeekdayBar[] = rows.map((row, day) => ({
      day: row.longDay,
      count: row.total,
      sharePct: totalOrders > 0 ? pctText((row.total / totalOrders) * 100) : '0%',
      width: maxDay > 0 ? Math.round((row.total / maxDay) * 100) : 0,
      isPeak: day === hm.peakDay,
    }));

    const peakDay = hm.peakDay >= 0 && hm.peakDay < 7 ? WEEKDAYS_LONG[hm.peakDay] : null;
    const peakHour =
      hm.peakHour >= 0 && hm.peakHour < 24 ? `${hourLabel(hm.peakHour)} to ${hourLabel((hm.peakHour + 1) % 24)}` : null;

    return { hasData: max > 0, rows, peakDay, peakHour, bars, totalOrders };
  });
}
