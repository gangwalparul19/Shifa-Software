import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { LedgerPeriod, LedgerService } from './ledger.service';
import {
  FinancialYear,
  ProfitAndLoss,
  drcrLabel,
  natureLabel,
  naturePillClass,
} from './ledger.model';

/** Which period control is driving the report — a financial year, or a from/to range. */
type PeriodMode = 'fy' | 'range';

/**
 * Profit &amp; Loss (Reqs 4.2–4.5, 5.3, 11.3, 13.4). Read-only view for the
 * ADMIN, ACCOUNTANT, and CA roles that presents the income side and the
 * expenses side as Tally-style grouped rows with expandable drill-down into
 * child groups and ledger leaves, the gross-profit subtotal (only where the
 * Chart of Accounts distinguishes Direct groups), and the net-profit / net-loss
 * result for the period.
 *
 * <p>The period is chosen either by financial year or by an explicit from/to
 * date range (mirroring {@link TrialBalanceComponent}), with an optional
 * comparative prior-period toggle (Req 7) that surfaces a prior-period column.
 */
@Component({
  selector: 'admin-profit-and-loss',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './profit-and-loss.component.html',
  styleUrl: './profit-and-loss.component.css',
})
export class ProfitAndLossComponent implements OnInit {
  private readonly ledger = inject(LedgerService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<ProfitAndLoss | null>(null);

  /** Financial years for the FY picker (most recent first). */
  protected readonly financialYears = signal<FinancialYear[]>([]);

  /** Whether the FY or the date-range control drives the report. */
  protected readonly periodMode = signal<PeriodMode>('fy');

  /** The selected financial-year id (FY mode). */
  protected readonly financialYearId = signal<number | null>(null);

  /** The explicit from/to dates (range mode, ISO yyyy-MM-dd). */
  protected readonly from = signal('');
  protected readonly to = signal('');

  /** Whether the comparative prior-period column is requested (Req 7). */
  protected readonly comparative = signal(false);

  /** The set of expanded account-group ids (drill-down state), keyed by groupId. */
  private readonly expanded = signal<Set<number>>(new Set());

  /** A human label for the active period, shown in the header. */
  protected readonly periodLabel = computed(() => {
    if (this.periodMode() === 'range') {
      const f = this.from();
      const t = this.to();
      return f && t ? `${f} to ${t}` : 'Custom range';
    }
    const fy = this.financialYears().find((y) => y.id === this.financialYearId());
    return fy ? `FY ${fy.label}` : 'Current financial year';
  });

  // Re-exported pill/label helpers for the template.
  protected readonly naturePillClass = naturePillClass;
  protected readonly natureLabel = natureLabel;
  protected readonly drcrLabel = drcrLabel;

  ngOnInit(): void {
    // Default the range control to the current month, so switching to range mode
    // starts from sensible dates; the FY mode is the default view.
    const today = new Date();
    const first = new Date(today.getFullYear(), today.getMonth(), 1);
    this.from.set(this.iso(first));
    this.to.set(this.iso(today));

    this.ledger.listFinancialYears().subscribe({
      next: (years) => {
        this.financialYears.set(years);
        // Default to the most recent financial year when one exists.
        if (years.length > 0) {
          this.financialYearId.set(years[0].id);
        }
        this.load();
      },
      error: () => {
        // Financial years are a convenience for the picker; still load the
        // default (current-FY) statement so the report is usable.
        this.load();
      },
    });
  }

  /** Load the Profit & Loss statement for the currently selected period. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.ledger.profitAndLoss(this.currentPeriod(), this.comparative()).subscribe({
      next: (pl) => {
        this.data.set(pl);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(
          'Could not load the Profit & Loss statement. Please check the period and try again.',
        );
        this.loading.set(false);
      },
    });
  }

  /** Switch the period control between financial year and date range. */
  setMode(mode: PeriodMode): void {
    if (this.periodMode() === mode) {
      return;
    }
    this.periodMode.set(mode);
  }

  /** FY picker change handler. */
  onFinancialYearChanged(value: string): void {
    this.financialYearId.set(value ? Number(value) : null);
  }

  /** Date-range edit handler. */
  onDateEdited(which: 'from' | 'to', value: string): void {
    if (which === 'from') {
      this.from.set(value);
    } else {
      this.to.set(value);
    }
  }

  /** Toggle the comparative prior-period column and reload. */
  toggleComparative(): void {
    this.comparative.update((v) => !v);
    this.load();
  }

  /** Whether an account group is currently expanded in the drill-down. */
  isExpanded(groupId: number): boolean {
    return this.expanded().has(groupId);
  }

  /** Expand/collapse an account group's drill-down. */
  toggle(groupId: number): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      return next;
    });
  }

  /** Builds the {@link LedgerPeriod} from the active period control. */
  private currentPeriod(): LedgerPeriod {
    if (this.periodMode() === 'range') {
      return { from: this.from() || null, to: this.to() || null };
    }
    return { financialYearId: this.financialYearId() };
  }

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
    return (
      '₹' +
      this.n(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    );
  }

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
