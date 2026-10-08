import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { LedgerPeriod, LedgerService } from './ledger.service';
import { CashFlow, FinancialYear, drcrLabel } from './ledger.model';

/** Which period control is driving the report — a financial year, or a from/to range. */
type PeriodMode = 'fy' | 'range';

/**
 * Cash Flow statement (Reqs 6.1–6.4, 11.3). Read-only view for the ADMIN,
 * ACCOUNTANT, and CA roles (no mutations) presenting the direct-method
 * cash-movement statement over the Cash and Bank ledgers: the
 * <em>opening → inflows → outflows → net cash movement → closing</em> layout
 * (opening/closing shown as a Dr/Cr side + magnitude), with the per Cash/Bank
 * ledger drill-down (each ledger's opening, inflows, outflows, and closing).
 *
 * <p>The period is chosen either by financial year or by an explicit from/to
 * date range, and an optional comparative prior-period toggle surfaces the
 * immediately-preceding equal-length window's figures alongside the current
 * ones — mirroring {@link TrialBalanceComponent}'s conventions and styling.
 */
@Component({
  selector: 'admin-cash-flow',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './cash-flow.component.html',
  styleUrl: './cash-flow.component.css',
})
export class CashFlowComponent implements OnInit {
  private readonly ledger = inject(LedgerService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<CashFlow | null>(null);

  /** Financial years for the FY picker (most recent first). */
  protected readonly financialYears = signal<FinancialYear[]>([]);

  /** Whether the FY or the date-range control drives the report. */
  protected readonly periodMode = signal<PeriodMode>('fy');

  /** The selected financial-year id (FY mode). */
  protected readonly financialYearId = signal<number | null>(null);

  /** The explicit from/to dates (range mode, ISO yyyy-MM-dd). */
  protected readonly from = signal('');
  protected readonly to = signal('');

  /** Whether the comparative prior-period column is requested (off by default, Req 7.2). */
  protected readonly comparative = signal(false);

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

  // Re-exported label helper for the template.
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
        // default (current-FY) cash flow so the report is usable.
        this.load();
      },
    });
  }

  /** Load the cash flow statement for the currently selected period. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.ledger.cashFlow(this.currentPeriod(), this.comparative()).subscribe({
      next: (cf) => {
        this.data.set(cf);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the Cash Flow statement. Please check the period and try again.');
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

  /** Comparative prior-period toggle handler — reloads so the prior figures are fetched. */
  onComparativeToggled(on: boolean): void {
    this.comparative.set(on);
    this.load();
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
