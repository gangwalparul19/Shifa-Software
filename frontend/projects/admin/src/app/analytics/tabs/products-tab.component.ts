import { Component, computed, input, signal } from '@angular/core';
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
import { readPageSize, writePageSize } from '../../shared/page-size.util';
import { PaginationComponent } from '../../shared/pagination.component';
import { StatePanelComponent } from '../../shared/state-panel.component';
import { changeIcon, changeLabel, changeTone } from '../analytics-format.util';
import { AffinityPair, ProductPerformanceReport, ProductRow } from '../analytics.model';

/** How many products the Pareto chart shows. */
const PARETO_COUNT = 10;

/** ApexCharts option bundle for the products Pareto chart (columns plus a running-share line). */
interface ParetoOptions {
  series: ApexAxisChartSeries;
  chart: ApexChart;
  colors: string[];
  dataLabels: ApexDataLabels;
  stroke: ApexStroke;
  plotOptions: ApexPlotOptions;
  markers: ApexMarkers;
  xaxis: ApexXAxis;
  yaxis: ApexYAxis[];
  legend: ApexLegend;
  grid: ApexGrid;
  tooltip: ApexTooltip;
}

/**
 * The Products tab of the Analytics page: what sold, which products carry the
 * revenue (ABC classes and a Pareto chart), how each product is trending against
 * the previous period, and which products are bought together.
 */
@Component({
  selector: 'admin-analytics-products',
  standalone: true,
  imports: [NgApexchartsModule, PaginationComponent, StatePanelComponent],
  templateUrl: './products-tab.component.html',
  styleUrl: './products-tab.component.css',
})
export class ProductsTabComponent {
  readonly report = input.required<ProductPerformanceReport>();

  private readonly reducedMotion =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
      : false;

  /** Products by revenue, biggest first (the backend already sends them in this order). */
  protected readonly sorted = computed<ProductRow[]>(() =>
    [...this.report().products].sort((a, b) => b.revenue - a.revenue),
  );

  protected readonly hasSales = computed(() => this.sorted().length > 0);

  protected readonly periodLabel = computed(() => {
    const r = this.report();
    return `${formatDateLabel(r.from)} to ${formatDateLabel(r.to)}`;
  });

  // --- Main table paging (client-side) ------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('analyticsProducts', 10));
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.sorted().length / this.size())));
  /** The page shown, kept inside the valid range when a new report has fewer products. */
  protected readonly currentPage = computed(() => Math.min(this.page(), this.totalPages() - 1));
  protected readonly pageRows = computed<ProductRow[]>(() => {
    const start = this.currentPage() * this.size();
    return this.sorted().slice(start, start + this.size());
  });

  setPage(page: number): void {
    this.page.set(Math.max(0, page));
  }

  setSize(size: number): void {
    this.size.set(size);
    writePageSize('analyticsProducts', size);
    this.page.set(0);
  }

  // --- Pareto chart -------------------------------------------------------
  protected readonly paretoLabel = computed(() => {
    const top = this.sorted().slice(0, PARETO_COUNT);
    if (top.length === 0) {
      return '';
    }
    const running = top[top.length - 1].cumulativeSharePct;
    return (
      `Chart of the top ${top.length} products by revenue. Together they make ${this.pct(running)} ` +
      'of product revenue. The table below lists the same figures.'
    );
  });

  protected readonly paretoChart = computed<ParetoOptions | null>(() => {
    const top = this.sorted().slice(0, PARETO_COUNT);
    if (top.length === 0) {
      return null;
    }
    return {
      series: [
        { name: 'Revenue', type: 'column', data: top.map((p) => Math.round(p.revenue)) },
        {
          name: 'Running share of revenue',
          type: 'line',
          data: top.map((p) => Math.round(p.cumulativeSharePct * 10) / 10),
        },
      ],
      chart: {
        type: 'line',
        height: 340,
        fontFamily: 'inherit',
        toolbar: { show: false },
        zoom: { enabled: false },
        animations: { enabled: !this.reducedMotion },
      },
      colors: ['#1f5d3f', '#d97706'],
      dataLabels: { enabled: false },
      stroke: { width: [0, 3], curve: 'smooth' },
      plotOptions: { bar: { columnWidth: '55%', borderRadius: 4 } },
      markers: { size: [0, 4] },
      xaxis: {
        categories: top.map((p) => p.name),
        labels: {
          rotate: -45,
          hideOverlappingLabels: true,
          trim: true,
          maxHeight: 110,
          style: { colors: '#6b7c74' },
        },
        axisBorder: { show: false },
        axisTicks: { show: false },
      },
      yaxis: [
        {
          seriesName: 'Revenue',
          labels: { formatter: (v: number) => formatCompactInr(v), style: { colors: '#6b7c74' } },
        },
        {
          seriesName: 'Running share of revenue',
          opposite: true,
          min: 0,
          max: 100,
          tickAmount: 5,
          labels: { formatter: (v: number) => `${Math.round(v)}%`, style: { colors: '#6b7c74' } },
        },
      ],
      legend: { position: 'top', horizontalAlign: 'right', fontFamily: 'inherit' },
      grid: { borderColor: 'rgba(15,51,36,0.08)', strokeDashArray: 4 },
      tooltip: {
        theme: 'light',
        shared: true,
        intersect: false,
        y: [{ formatter: (v: number) => formatInr(v, 0) }, { formatter: (v: number) => `${v}%` }],
      },
    };
  });

  // --- Display helpers ----------------------------------------------------

  protected money(value: number | null | undefined): string {
    return formatInr(value, 0);
  }

  protected num(value: number | null | undefined): string {
    return (value ?? 0).toLocaleString('en-IN');
  }

  protected pct(value: number | null | undefined): string {
    return `${(value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}%`;
  }

  protected avg(value: number | null | undefined): string {
    return (value ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 1 });
  }

  /** A bar width from a 0-100 percentage, kept inside the track. */
  protected barWidth(value: number | null | undefined): number {
    return Math.min(100, Math.max(0, value ?? 0));
  }

  protected growthText(value: number | null | undefined): string {
    return changeLabel(value ?? null);
  }

  protected growthTone(value: number | null | undefined): string {
    return changeTone(value ?? null);
  }

  protected growthIcon(value: number | null | undefined): string {
    return changeIcon(value ?? null);
  }

  /** Badge colour for an ABC class; the letter itself carries the meaning. */
  protected abcTone(abcClass: ProductRow['abcClass']): string {
    if (abcClass === 'A') {
      return 'tone-green';
    }
    return abcClass === 'B' ? 'tone-blue' : 'tone-grey';
  }

  protected abcTitle(abcClass: ProductRow['abcClass']): string {
    if (abcClass === 'A') {
      return 'Class A: a core product';
    }
    return abcClass === 'B' ? 'Class B: a supporting product' : 'Class C: part of the long tail';
  }

  /** "4.2x" for a lift of 4.24. */
  protected liftValue(pair: AffinityPair): string {
    return `${pair.lift.toLocaleString('en-IN', { maximumFractionDigits: 1 })}x`;
  }

  protected liftNote(pair: AffinityPair): string {
    if (pair.lift >= 1.05) {
      return 'more often than chance';
    }
    return pair.lift <= 0.95 ? 'less often than chance' : 'about as often as chance';
  }
}
