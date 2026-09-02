import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { FilingService } from './filing.service';
import {
  DrillDownRow,
  ReconciliationFigure,
  ReconciliationSummary,
  directionLabel,
  reconciledIndicatorClass,
} from './filing.model';

/** The quick period presets available on the reconciliation view. */
type PresetKey = 'this-month' | 'last-month' | 'custom';

/**
 * CA GST reconciliation (Phase 3, Reqs 8.5, 9.1–9.7). Presents the five compared figures for a
 * return period — GSTR-1 output tax, GSTR-3B output tax, ITC, net GST payable and taxable outward
 * turnover — each with its return value, ledger/statement value, signed difference, direction and a
 * per-figure reconciled/unreconciled indicator, plus a per-period reconciled banner. A figure whose
 * comparison is unavailable shows a "comparison unavailable" state instead of a misleading value.
 * Clicking a figure drills down into the contributing orders / ledger vouchers behind it.
 */
@Component({
  selector: 'admin-gst-reconciliation',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './gst-reconciliation.component.html',
  styleUrl: './gst-reconciliation.component.css',
})
export class GstReconciliationComponent implements OnInit {
  private readonly filing = inject(FilingService);
  private readonly toasts = inject(ToastService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<ReconciliationSummary | null>(null);

  /** The selected return period (1–12 month + full year); defaults to the current month. */
  protected readonly month = signal(1);
  protected readonly year = signal(2025);

  /** Which quick-preset is currently active (for chip highlighting). */
  protected readonly activePreset = signal<PresetKey>('this-month');

  /** The month options (1–12) for the picker. */
  protected readonly months = [
    { value: 1, label: 'January' },
    { value: 2, label: 'February' },
    { value: 3, label: 'March' },
    { value: 4, label: 'April' },
    { value: 5, label: 'May' },
    { value: 6, label: 'June' },
    { value: 7, label: 'July' },
    { value: 8, label: 'August' },
    { value: 9, label: 'September' },
    { value: 10, label: 'October' },
    { value: 11, label: 'November' },
    { value: 12, label: 'December' },
  ];

  /** A human label for the selected period, shown in the header. */
  protected readonly periodLabel = computed(() => {
    const m = this.months.find((x) => x.value === this.month());
    return `${m ? m.label : ''} ${this.year()}`.trim();
  });

  /**
   * An empty period has every compared figure at zero on both the return and ledger sides
   * (Req 9.5) — surfaced as a gentle note rather than an error.
   */
  protected readonly isEmptyPeriod = computed(() => {
    const figures = this.data()?.figures ?? [];
    return (
      figures.length > 0 &&
      figures.every((f) => this.n(f.returnValue) === 0 && this.n(f.ledgerValue) === 0)
    );
  });

  // --- Drill-down (contributing rows behind a figure) --------------------
  protected readonly drillOpen = signal(false);
  protected readonly drillTitle = signal('');
  protected readonly drillLoading = signal(false);
  protected readonly drillRows = signal<DrillDownRow[]>([]);

  /** Sum of the signed contributions across the drill-down list. */
  protected readonly drillTotal = computed(() =>
    this.drillRows().reduce((sum, r) => sum + this.n(r.signedContribution), 0),
  );

  ngOnInit(): void {
    this.setThisMonth();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.filing.reconcile(this.month(), this.year()).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the reconciliation. Please try again.');
        this.loading.set(false);
      },
    });
  }

  private applyPreset(preset: PresetKey, month: number, year: number): void {
    this.activePreset.set(preset);
    this.month.set(month);
    this.year.set(year);
    this.load();
  }

  setThisMonth(): void {
    const t = new Date();
    this.applyPreset('this-month', t.getMonth() + 1, t.getFullYear());
  }

  setLastMonth(): void {
    const t = new Date(new Date().getFullYear(), new Date().getMonth() - 1, 1);
    this.applyPreset('last-month', t.getMonth() + 1, t.getFullYear());
  }

  /** A manual edit of the month/year picker switches to the custom preset. */
  onMonthEdited(value: string): void {
    this.month.set(Number(value) || 1);
    this.activePreset.set('custom');
  }

  onYearEdited(value: string): void {
    this.year.set(Number(value) || new Date().getFullYear());
    this.activePreset.set('custom');
  }

  // --- Drill-down handlers -------------------------------------------------

  /** Open the drill-down for a compared figure (Reqs 9.3, 9.7). Skips figures without a comparison. */
  openDrill(figure: ReconciliationFigure): void {
    if (!figure.comparisonAvailable) {
      return;
    }
    this.drillTitle.set(figure.label);
    this.drillOpen.set(true);
    this.drillLoading.set(true);
    this.drillRows.set([]);
    this.filing.drillDown(this.month(), this.year(), String(figure.key)).subscribe({
      next: (rows) => {
        this.drillRows.set(rows);
        this.drillLoading.set(false);
      },
      error: () => {
        this.drillLoading.set(false);
        this.toasts.error('Could not load the contributing rows.');
      },
    });
  }

  closeDrill(): void {
    this.drillOpen.set(false);
  }

  // --- Presentation helpers ------------------------------------------------

  protected readonly directionLabel = directionLabel;
  protected readonly reconciledIndicatorClass = reconciledIndicatorClass;

  /** Parse a money-ish value (backend may send number or numeric string) to a number. */
  n(v: number | string | null | undefined): number {
    if (v === null || v === undefined) {
      return 0;
    }
    const num = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(num) ? num : 0;
  }

  /** Format a value as ₹ with 2 decimals. */
  money(v: number | string | null | undefined): string {
    return '₹' + this.n(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  /** Format a signed difference with an explicit +/- sign. */
  signedMoney(v: number | string | null | undefined): string {
    const num = this.n(v);
    const sign = num > 0 ? '+' : num < 0 ? '−' : '';
    return sign + '₹' + Math.abs(num).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
}
