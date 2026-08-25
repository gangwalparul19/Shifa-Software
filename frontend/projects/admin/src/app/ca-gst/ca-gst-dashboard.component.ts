import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { ToastService } from '../shared/toast.service';
import { GstService } from './gst.service';
import { GstDashboard, GstOrderRow, Gstr1ReturnResponse } from './gst.model';

/** The quick period presets available on the dashboard. */
type PresetKey = 'this-month' | 'last-month' | 'this-quarter' | 'this-fy' | 'last-fy' | 'custom';

/** The top-level view: the accounting dashboard or the portal-ready GSTR-1 return. */
type MainTab = 'dashboard' | 'gstr1';

/**
 * CA (Chartered Accountant) GST & accounting dashboard (CA GST dashboard,
 * Reqs 5, 6, 7). Shows the period money in/out, the GSTR-3B-style summary, and
 * the rate-wise / HSN-wise / state-wise outward supply summaries, with a CSV
 * export and an optional manual ITC for the net-payable estimate.
 */
@Component({
  selector: 'admin-ca-gst-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, PageHeaderComponent],
  templateUrl: './ca-gst-dashboard.component.html',
  styleUrl: './ca-gst-dashboard.component.css',
})
export class CaGstDashboardComponent implements OnInit {
  private readonly gst = inject(GstService);
  private readonly toasts = inject(ToastService);

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly data = signal<GstDashboard | null>(null);
  protected readonly exporting = signal(false);

  /** Which top-level view is showing (accounting dashboard vs GSTR-1). */
  protected readonly mainTab = signal<MainTab>('dashboard');

  // --- GSTR-1 (portal-ready outward return, Reqs 3.4, 5.5, 6.3) -----------
  protected readonly gstr1Loading = signal(false);
  protected readonly gstr1Error = signal<string | null>(null);
  protected readonly gstr1Data = signal<Gstr1ReturnResponse | null>(null);
  protected readonly gstr1Exporting = signal(false);

  /** HSN rows flagged as non-compliant (short HSN code, Req 3.4). */
  protected readonly hsnNonCompliant = computed(() =>
    (this.gstr1Data()?.hsn ?? []).filter((h) => !h.compliant),
  );

  /** Selected period (ISO yyyy-MM-dd); defaults set in ngOnInit to the current month. */
  protected readonly from = signal('');
  protected readonly to = signal('');

  /** Which quick-preset is currently active (for chip highlighting). */
  protected readonly activePreset = signal<PresetKey>('this-month');

  /** A human label for the active period, shown in the header. */
  protected readonly periodLabel = computed(() => {
    const map: Record<string, string> = {
      'this-month': 'This month',
      'last-month': 'Last month',
      'this-quarter': 'This quarter',
      'this-fy': 'This financial year',
      'last-fy': 'Last financial year',
      custom: 'Custom range',
    };
    return map[this.activePreset()] ?? 'Custom range';
  });

  /** Optional manual ITC the CA can enter for the net-payable estimate (Req 5.2). */
  protected readonly manualItc = signal(0);

  /** Net GST payable = output tax − manual ITC, floored at 0 (Req 5.3). */
  protected readonly netPayable = computed(() => {
    const out = this.n(this.data()?.report.summary.outputTotal ?? 0);
    return Math.max(0, out - (this.manualItc() || 0));
  });

  /** Intra-state taxable total (CGST+SGST supplies), summed from the state-wise rows. */
  protected readonly intraTaxable = computed(() =>
    (this.data()?.report.stateWise ?? [])
      .filter((r) => r.type === 'INTRA')
      .reduce((sum, r) => sum + this.n(r.taxable), 0),
  );

  /** Inter-state taxable total (IGST supplies), summed from the state-wise rows. */
  protected readonly interTaxable = computed(() =>
    (this.data()?.report.stateWise ?? [])
      .filter((r) => r.type === 'INTER')
      .reduce((sum, r) => sum + this.n(r.taxable), 0),
  );

  // --- Drill-down (orders behind a summary figure) ------------------------
  protected readonly drillOpen = signal(false);
  protected readonly drillTitle = signal('');
  protected readonly drillLoading = signal(false);
  protected readonly drillOrders = signal<GstOrderRow[]>([]);

  /** Total remaining dues across the drill-down list. */
  protected readonly drillRemaining = computed(() =>
    this.drillOrders().reduce((sum, o) => sum + this.n(o.remaining), 0),
  );

  /** Total customer-owed (non-COD) balance across the drill-down list. */
  protected readonly drillCustomerRemaining = computed(() =>
    this.drillOrders().reduce((sum, o) => sum + this.n(o.customerRemaining), 0),
  );

  /** Total COD pending from the courier across the drill-down list. */
  protected readonly drillCodPending = computed(() =>
    this.drillOrders().reduce((sum, o) => sum + this.n(o.codPending), 0),
  );

  ngOnInit(): void {
    const today = new Date();
    const first = new Date(today.getFullYear(), today.getMonth(), 1);
    this.from.set(this.iso(first));
    this.to.set(this.iso(today));
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.gst.dashboard(this.from(), this.to()).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the GST dashboard. Please check the dates and try again.');
        this.loading.set(false);
      },
    });
    // Keep the GSTR-1 view in sync when the period changes while it is showing.
    if (this.mainTab() === 'gstr1') {
      this.loadGstr1();
    }
  }

  /** Switch the top-level view; lazy-load GSTR-1 the first time it is shown. */
  setTab(tab: MainTab): void {
    this.mainTab.set(tab);
    if (tab === 'gstr1' && this.gstr1Data() === null && !this.gstr1Loading()) {
      this.loadGstr1();
    }
  }

  /** Load the portal-ready GSTR-1 return for the selected period. */
  loadGstr1(): void {
    this.gstr1Loading.set(true);
    this.gstr1Error.set(null);
    this.gst.gstr1(this.from(), this.to()).subscribe({
      next: (r) => {
        this.gstr1Data.set(r);
        this.gstr1Loading.set(false);
      },
      error: () => {
        this.gstr1Error.set('Could not load the GSTR-1 return. Please check the dates and try again.');
        this.gstr1Loading.set(false);
      },
    });
  }

  /** Download the GSTR-1 export: {@code csv} = ZIP of section CSVs, {@code json} = portal JSON. */
  downloadGstr1(format: 'csv' | 'json'): void {
    this.gstr1Exporting.set(true);
    this.gst.exportGstr1(this.from(), this.to(), format).subscribe({
      next: (blob) => {
        this.gstr1Exporting.set(false);
        const ext = format === 'csv' ? 'zip' : 'json';
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `gstr1-${this.from()}-to-${this.to()}.${ext}`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => {
        this.gstr1Exporting.set(false);
        this.toasts.error('Could not export GSTR-1. Please try again.');
      },
    });
  }

  /** Sum the taxable value across a set of section rows. */
  sumTaxable(rows: ReadonlyArray<{ taxable: number | string }>): number {
    return rows.reduce((sum, r) => sum + this.n(r.taxable), 0);
  }

  /** Sum the total tax (CGST + SGST + IGST) across a set of section rows. */
  sumTax(rows: ReadonlyArray<{ cgst?: number | string; sgst?: number | string; igst?: number | string }>): number {
    return rows.reduce((sum, r) => sum + this.n(r.cgst) + this.n(r.sgst) + this.n(r.igst), 0);
  }

  /** April 1 of the Indian financial year that contains {@code d}. */
  private fyStart(d: Date): Date {
    const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1;
    return new Date(y, 3, 1);
  }

  private applyPreset(preset: PresetKey, from: Date, to: Date): void {
    this.activePreset.set(preset);
    this.from.set(this.iso(from));
    this.to.set(this.iso(to));
    this.load();
  }

  setThisMonth(): void {
    const t = new Date();
    this.applyPreset('this-month', new Date(t.getFullYear(), t.getMonth(), 1), t);
  }

  setLastMonth(): void {
    const t = new Date();
    this.applyPreset('last-month',
      new Date(t.getFullYear(), t.getMonth() - 1, 1),
      new Date(t.getFullYear(), t.getMonth(), 0));
  }

  /** The current Indian FY quarter (Q1 Apr-Jun, Q2 Jul-Sep, Q3 Oct-Dec, Q4 Jan-Mar). */
  setThisQuarter(): void {
    const t = new Date();
    const s = this.fyStart(t);
    const qi = Math.floor((((t.getMonth() - 3) + 12) % 12) / 3); // 0..3 within the FY
    const qStart = new Date(s.getFullYear(), 3 + qi * 3, 1); // month overflow rolls the year
    this.applyPreset('this-quarter', qStart, t);
  }

  /** This financial year to date (Apr 1 → today). */
  setThisFy(): void {
    const t = new Date();
    this.applyPreset('this-fy', this.fyStart(t), t);
  }

  /** The previous, completed financial year (Apr 1 → Mar 31). */
  setLastFy(): void {
    const s = this.fyStart(new Date());
    this.applyPreset('last-fy',
      new Date(s.getFullYear() - 1, 3, 1),
      new Date(s.getFullYear(), 2, 31));
  }

  /** A manual edit of a date field switches to the custom preset. */
  onDateEdited(which: 'from' | 'to', value: string): void {
    if (which === 'from') {
      this.from.set(value);
    } else {
      this.to.set(value);
    }
    this.activePreset.set('custom');
  }

  export(format: 'csv' | 'pdf'): void {
    this.exporting.set(true);
    this.gst.exportReport(this.from(), this.to(), format).subscribe({
      next: (blob) => {
        this.exporting.set(false);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `gst-report-${this.from()}-to-${this.to()}.${format}`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => {
        this.exporting.set(false);
        this.toasts.error('Could not export the GST report. Please try again.');
      },
    });
  }

  // --- Drill-down handlers -------------------------------------------------

  drillByState(state: string): void {
    this.openDrill(`Orders — ${state}`, { state });
  }

  drillByRate(rate: number | string): void {
    this.openDrill(`Orders — GST ${this.n(rate)}%`, { rate });
  }

  drillByHsn(hsn: string): void {
    this.openDrill(`Orders — HSN ${hsn}`, { hsn });
  }

  /** All orders in the period (used by the money KPI cards). */
  drillAll(title: string): void {
    this.openDrill(title, {});
  }

  private openDrill(title: string, filter: { state?: string; rate?: number | string; hsn?: string }): void {
    this.drillTitle.set(title);
    this.drillOpen.set(true);
    this.drillLoading.set(true);
    this.drillOrders.set([]);
    this.gst.orders(this.from(), this.to(), filter).subscribe({
      next: (rows) => {
        this.drillOrders.set(rows);
        this.drillLoading.set(false);
      },
      error: () => {
        this.drillLoading.set(false);
        this.toasts.error('Could not load the linked orders.');
      },
    });
  }

  closeDrill(): void {
    this.drillOpen.set(false);
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
    return '₹' + this.n(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  private iso(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
