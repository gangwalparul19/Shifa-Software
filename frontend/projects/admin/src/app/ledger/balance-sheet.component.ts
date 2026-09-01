import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { LedgerPeriod, LedgerService } from './ledger.service';
import {
  BalanceSheet,
  FinancialYear,
  StatementNode,
  drcrLabel,
  naturePillClass,
} from './ledger.model';

/** Which period control is driving the statement — a financial year, or a from/to range. */
type PeriodMode = 'fy' | 'range';

/**
 * Balance Sheet (Reqs 2.1, 2.3, 3.4, 5.3, 11.3, 13.4). Read-only view for the
 * ADMIN, ACCOUNTANT, and CA roles that presents, as at a date, the assets side
 * and the liabilities-and-equity side as Tally-style grouped rows with
 * expandable drill-down into nested child groups and ledger leaves, the injected
 * current-period retained-earnings line within equity, each side's total, and a
 * prominent balanced / difference banner.
 *
 * <p>The period is chosen either by financial year or by an explicit from/to
 * date range (the As_At_Date is the period's to-date), with an optional
 * comparative prior-period toggle — mirroring {@code TrialBalanceComponent}.
 */
@Component({
  selector: 'admin-balance-sheet',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './balance-sheet.component.html',
  styleUrl: './balance-sheet.component.css',
})
export class BalanceSheetComponent implements OnInit {
  private readonly ledger = inject(LedgerService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<BalanceSheet | null>(null);

  /** Financial years for the FY picker (most recent first). */
  protected readonly financialYears = signal<FinancialYear[]>([]);

  /** Whether the FY or the date-range control drives the statement. */
  protected readonly periodMode = signal<PeriodMode>('fy');

  /** The selected financial-year id (FY mode). */
  protected readonly financialYearId = signal<number | null>(null);

  /** The explicit from/to dates (range mode, ISO yyyy-MM-dd). */
  protected readonly from = signal('');
  protected readonly to = signal('');

  /** Whether to request a comparative prior-period column (off by default, Req 7). */
  protected readonly comparative = signal(false);

  /** The set of expanded group ids for drill-down (Reqs 5.3, 13.4). */
  protected readonly expanded = signal<Set<number>>(new Set<number>());

  /** A human label for the active period, shown in the header. */
  protected readonly periodLabel = computed(() => {
    if (this.periodMode() === 'range') {
      const f = this.from();
      const t = this.to();
      return f && t ? `As at ${t}` : 'Custom range';
    }
    const fy = this.financialYears().find((y) => y.id === this.financialYearId());
    return fy ? `FY ${fy.label}` : 'Current financial year';
  });

  /** The absolute difference amount (for display when unbalanced). */
  protected readonly difference = computed(() => Math.abs(this.n(this.data()?.difference)));

  // Re-exported pill/label helpers for the template.
  protected readonly naturePillClass = naturePillClass;
  protected readonly drcrLabel = drcrLabel;

  ngOnInit(): void {
    // Default the range control to the current financial year to date, so
    // switching to range mode starts from sensible dates; FY mode is default.
    const today = new Date();
    const fyStart = new Date(today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1, 3, 1);
    this.from.set(this.iso(fyStart));
    this.to.set(this.iso(today));

    this.ledger.listFinancialYears().subscribe({
      next: (years) => {
        this.financialYears.set(years);
        if (years.length > 0) {
          this.financialYearId.set(years[0].id);
        }
        this.load();
      },
      error: () => {
        // Financial years are a convenience for the picker; still load the
        // default (current-FY) balance sheet so the statement is usable.
        this.load();
      },
    });
  }

  /** Load the balance sheet for the currently selected period. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.ledger.balanceSheet(this.currentPeriod(), this.comparative()).subscribe({
      next: (bs) => {
        this.data.set(bs);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the Balance Sheet. Please check the period and try again.');
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

  /** Whether a group node is currently expanded. */
  isExpanded(groupId: number): boolean {
    return this.expanded().has(groupId);
  }

  /** Toggle a group node's drill-down (child groups + ledger leaves). */
  toggleGroup(groupId: number): void {
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

  /** Whether a node has any children to drill into (child groups or ledger leaves). */
  hasChildren(node: StatementNode): boolean {
    return (node.childGroups?.length ?? 0) > 0 || (node.ledgers?.length ?? 0) > 0;
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
      '₹' + this.n(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    );
  }

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
