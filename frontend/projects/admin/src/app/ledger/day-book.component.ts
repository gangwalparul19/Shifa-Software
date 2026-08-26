import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { LedgerPeriod, LedgerService } from './ledger.service';
import {
  DayBook,
  FinancialYear,
  LedgerAccount,
  VOUCHER_TYPES,
  VoucherType,
  drcrLabel,
  voucherTypeLabel,
} from './ledger.model';

/** How the reporting period is chosen — a financial year or an explicit date range (Req 4.4). */
type PeriodMode = 'fy' | 'range';

/**
 * Day Book (Req 13) — a chronological listing of every posted voucher in a
 * period, each row showing its reference, date, type, narration, and the
 * debit/credit amounts of its lines.
 *
 * <p>The period is chosen either as a financial year (from
 * {@link LedgerService.listFinancialYears}) or as an explicit from/to date
 * range, with an optional voucher-type filter ("All" by default). This is a
 * read-only view available to ADMIN / ACCOUNTANT / CA (guarded by the route);
 * it never mutates the ledger, so no role-gating of actions is required here.
 */
@Component({
  selector: 'admin-day-book',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './day-book.component.html',
  styleUrl: './day-book.component.css',
})
export class DayBookComponent implements OnInit {
  private readonly ledger = inject(LedgerService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<DayBook | null>(null);

  /** The financial years available for the period picker (most recent first). */
  protected readonly financialYears = signal<FinancialYear[]>([]);

  /** Ledger id → name, so voucher lines can name their account. */
  protected readonly ledgerNames = signal<Map<number, string>>(new Map());

  /** The supported voucher types for the filter dropdown. */
  protected readonly voucherTypes = VOUCHER_TYPES;

  // --- Period + filter controls -------------------------------------------
  protected readonly periodMode = signal<PeriodMode>('fy');
  protected readonly selectedFyId = signal<number | null>(null);
  protected readonly from = signal('');
  protected readonly to = signal('');
  /** The selected voucher-type filter; empty string = "All" (no filter). */
  protected readonly voucherType = signal<VoucherType | ''>('');

  /** Total of all debit amounts across every line in the loaded period. */
  protected readonly totalDebit = computed(() =>
    (this.data()?.rows ?? []).reduce(
      (sum, r) => sum + r.lines.reduce((s, l) => s + this.n(l.debit), 0),
      0,
    ),
  );

  /** Total of all credit amounts across every line in the loaded period. */
  protected readonly totalCredit = computed(() =>
    (this.data()?.rows ?? []).reduce(
      (sum, r) => sum + r.lines.reduce((s, l) => s + this.n(l.credit), 0),
      0,
    ),
  );

  /** A human label for the currently loaded period, shown in the header. */
  protected readonly periodLabel = computed(() => {
    const d = this.data();
    if (!d) {
      return '';
    }
    if (d.financialYearId) {
      const fy = this.financialYears().find((f) => f.id === d.financialYearId);
      return fy ? `FY ${fy.label}` : `${d.from} to ${d.to}`;
    }
    return `${d.from} to ${d.to}`;
  });

  ngOnInit(): void {
    // Default the date range to the current month (used if there are no FYs).
    const today = new Date();
    this.from.set(this.iso(new Date(today.getFullYear(), today.getMonth(), 1)));
    this.to.set(this.iso(today));

    // Load the ledger-name map (best-effort — lines still render without it).
    this.ledger.listLedgers().subscribe({
      next: (ledgers: LedgerAccount[]) =>
        this.ledgerNames.set(new Map(ledgers.map((l) => [l.id, l.name]))),
      error: () => {
        /* names are optional; ignore */
      },
    });

    // Load the financial years, pick the most recent, then load the Day Book.
    this.ledger.listFinancialYears().subscribe({
      next: (years) => {
        this.financialYears.set(years);
        if (years.length > 0) {
          this.periodMode.set('fy');
          this.selectedFyId.set(years[0].id);
        } else {
          this.periodMode.set('range');
        }
        this.load();
      },
      error: () => {
        // Fall back to a date-range load even if the FY list fails.
        this.periodMode.set('range');
        this.load();
      },
    });
  }

  /** Loads the Day Book for the selected period + voucher-type filter. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const period = this.currentPeriod();
    const type = this.voucherType() || null;
    this.ledger.dayBook(period, type).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the Day Book. Please check the period and try again.');
        this.loading.set(false);
      },
    });
  }

  /** Switch between the financial-year and date-range period pickers. */
  setPeriodMode(mode: PeriodMode): void {
    this.periodMode.set(mode);
  }

  /** Resolves the current controls into a {@link LedgerPeriod} for the API. */
  private currentPeriod(): LedgerPeriod {
    if (this.periodMode() === 'fy' && this.selectedFyId() !== null) {
      return { financialYearId: this.selectedFyId() };
    }
    return { from: this.from() || null, to: this.to() || null };
  }

  /** The account name for a line's ledger id (falls back to `#id`). */
  ledgerName(id: number): string {
    return this.ledgerNames().get(id) ?? `#${id}`;
  }

  /** Short accountant's label for a voucher type. */
  typeLabel(type: VoucherType | string): string {
    return voucherTypeLabel(type);
  }

  /** `Dr` / `Cr` label helper (used for the totals footer). */
  drcr = drcrLabel;

  /** Tabler badge class for a voucher type (subtle, colour-coded by family). */
  typePillClass(type: VoucherType | string): string {
    switch (type) {
      case 'SALES':
      case 'RECEIPT':
        return 'bg-green-lt';
      case 'PURCHASE':
      case 'PAYMENT':
        return 'bg-red-lt';
      case 'CONTRA':
        return 'bg-blue-lt';
      case 'DEBIT_NOTE':
      case 'CREDIT_NOTE':
        return 'bg-yellow-lt';
      default:
        return 'bg-secondary-lt';
    }
  }

  /** Parse a money-ish value (backend may send a number or a numeric string). */
  n(v: number | string | null | undefined): number {
    if (v === null || v === undefined) {
      return 0;
    }
    const num = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(num) ? num : 0;
  }

  /** Format a value as ₹ with 2 decimals, or a blank for zero/empty. */
  money(v: number | string | null | undefined): string {
    const num = this.n(v);
    return '₹' + num.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
