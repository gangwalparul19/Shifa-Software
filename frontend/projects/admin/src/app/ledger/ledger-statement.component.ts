import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { LedgerPeriod, LedgerService } from './ledger.service';
import {
  LedgerAccount,
  LedgerBalance,
  LedgerStatement,
  FinancialYear,
  drcrLabel,
  naturePillClass,
  natureLabel,
  voucherTypeLabel,
} from './ledger.model';

/** How the reporting period is chosen — a financial year, or an explicit date range. */
type PeriodMode = 'fy' | 'range';

/**
 * Ledger statement — the account statement for a single ledger account over a
 * period (Req 12). Shows the opening balance (side + magnitude), a chronological
 * table of the account's voucher lines each with its running balance after
 * (Reqs 12.1, 12.2), and the closing balance (Req 12.3). When the account has no
 * lines in the period the closing balance equals the opening balance (Req 12.4).
 *
 * <p>Read-only view for ADMIN / ACCOUNTANT / CA. The account selector is
 * populated from {@link LedgerService.listLedgers} and the period from a
 * financial-year dropdown ({@link LedgerService.listFinancialYears}) or a
 * from/to date range, mirroring the CA GST dashboard conventions.
 */
@Component({
  selector: 'admin-ledger-statement',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './ledger-statement.component.html',
  styleUrl: './ledger-statement.component.css',
})
export class LedgerStatementComponent implements OnInit {
  private readonly ledger = inject(LedgerService);

  // Re-exported template helpers.
  protected readonly drcrLabel = drcrLabel;
  protected readonly naturePillClass = naturePillClass;
  protected readonly natureLabel = natureLabel;
  protected readonly voucherTypeLabel = voucherTypeLabel;

  /** The postable ledger accounts to choose from. */
  protected readonly ledgers = signal<LedgerAccount[]>([]);
  /** The available financial years (most recent first). */
  protected readonly financialYears = signal<FinancialYear[]>([]);

  /** The selected ledger account id (null until chosen). */
  protected readonly ledgerId = signal<number | null>(null);

  /** Whether the period is picked by financial year or an explicit date range. */
  protected readonly periodMode = signal<PeriodMode>('fy');
  /** Selected financial-year id (used when {@link periodMode} is `fy`). */
  protected readonly financialYearId = signal<number | null>(null);
  /** Range bounds (ISO yyyy-MM-dd) used when {@link periodMode} is `range`. */
  protected readonly from = signal('');
  protected readonly to = signal('');

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly statement = signal<LedgerStatement | null>(null);

  /** True once the metadata (ledgers + FYs) has loaded and a ledger can be picked. */
  protected readonly metaLoading = signal(true);

  /** True when the loaded statement has no voucher lines in the period (Req 12.4). */
  protected readonly noEntries = computed(() => (this.statement()?.rows.length ?? 0) === 0);

  ngOnInit(): void {
    // Default the range to the current Indian financial year (Apr 1 → today).
    const today = new Date();
    const fyStartYear = today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1;
    this.from.set(this.iso(new Date(fyStartYear, 3, 1)));
    this.to.set(this.iso(today));
    this.loadMeta();
  }

  /** Loads the ledger accounts and financial years for the pickers. */
  private loadMeta(): void {
    this.metaLoading.set(true);
    this.ledger.listLedgers().subscribe({
      next: (rows) => {
        this.ledgers.set(rows);
        this.metaLoading.set(false);
      },
      error: () => {
        this.error.set('Could not load the ledger accounts. Please try again.');
        this.metaLoading.set(false);
      },
    });
    this.ledger.listFinancialYears().subscribe({
      next: (rows) => {
        this.financialYears.set(rows);
        // Default the FY selection to the most recent one.
        if (rows.length && this.financialYearId() === null) {
          this.financialYearId.set(rows[0].id);
        }
      },
      error: () => {
        // FYs are optional for the range mode; a failure just leaves the dropdown empty.
      },
    });
  }

  /** Sets the selected ledger from the account dropdown and (re)loads the statement. */
  onLedgerChange(value: string): void {
    const id = value ? Number(value) : null;
    this.ledgerId.set(Number.isFinite(id as number) ? (id as number) : null);
    this.load();
  }

  /** Switches the period mode and reloads if a ledger is selected. */
  setPeriodMode(mode: PeriodMode): void {
    this.periodMode.set(mode);
    this.load();
  }

  onFinancialYearChange(value: string): void {
    const id = value ? Number(value) : null;
    this.financialYearId.set(Number.isFinite(id as number) ? (id as number) : null);
    this.load();
  }

  onDateEdited(which: 'from' | 'to', value: string): void {
    if (which === 'from') {
      this.from.set(value);
    } else {
      this.to.set(value);
    }
  }

  /** Builds the reporting period from the current mode + selections. */
  private period(): LedgerPeriod {
    if (this.periodMode() === 'fy') {
      return { financialYearId: this.financialYearId() };
    }
    return { from: this.from() || null, to: this.to() || null };
  }

  /** Loads the statement for the selected ledger + period. */
  load(): void {
    const id = this.ledgerId();
    if (id === null) {
      this.statement.set(null);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.ledger.ledgerStatement(id, this.period()).subscribe({
      next: (s) => {
        this.statement.set(s);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the ledger statement. Please check the period and try again.');
        this.loading.set(false);
      },
    });
  }

  /** A balance as "₹magnitude Dr/Cr", or a dash when there is no balance. */
  balanceText(balance: LedgerBalance | null | undefined): string {
    if (!balance) {
      return '—';
    }
    return `${this.money(balance.magnitude)} ${drcrLabel(balance.side)}`;
  }

  /** Parse a money-ish value (backend may send a number or numeric string) to a number. */
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

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
