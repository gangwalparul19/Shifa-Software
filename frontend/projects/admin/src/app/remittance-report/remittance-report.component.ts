import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { formatInr } from '../shared/inr.pipe';
import { asOfFilename, reportFilename } from '../shared/report-filename.util';
import { addIstDays, monthEndIst, monthStartIst, todayIst, weekStartIst } from '../shared/business-date.util';
import {
  PartialSettlement,
  ReconciliationService,
  RemittanceBatch,
  RemittanceBatchLine,
  RemittanceReportSummary,
  RemittanceRowStatus,
} from '../reconciliation/reconciliation.service';

/** The quick date-range presets for the report window. */
type PresetKey = 'this-week' | 'last-week' | 'this-month' | 'last-month' | 'custom';

/**
 * Weekly COD settlement report (V99).
 *
 * <p>A dedicated, client-facing page over the persisted courier-remittance
 * imports: KPI cards for the selected period (orders settled, partially settled,
 * shortfall, money remitted/settled, and the courier COD still outstanding), a
 * period filter (This week / Last week / This month / Last month / Custom), the
 * list of committed import batches, and a per-batch details report with each
 * order's expected vs remitted amount, a shortfall highlight, and a settled
 * icon. Read-only — the actual settling happens in the Reconciliation page's
 * "Import remittance" action, which this report then summarises.
 */
@Component({
  selector: 'admin-remittance-report',
  imports: [RouterLink, PageHeaderComponent, StatePanelComponent, PaginationComponent, IstDatePipe],
  templateUrl: './remittance-report.component.html',
  styleUrl: './remittance-report.component.css',
})
export class RemittanceReportComponent implements OnInit {
  private readonly service = inject(ReconciliationService);
  private readonly router = inject(Router);

  // --- Period ------------------------------------------------------------
  protected readonly preset = signal<PresetKey>('this-week');
  protected readonly from = signal<string>('');
  protected readonly to = signal<string>('');

  // --- Data --------------------------------------------------------------
  protected readonly summary = signal<RemittanceReportSummary | null>(null);
  protected readonly batches = signal<RemittanceBatch[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Which list the page is showing: committed imports, or the partial follow-up list. */
  protected readonly view = signal<'imports' | 'partial'>('imports');
  /** All partially-settled COD orders with a shortfall (across all time) — chase next cycle. */
  protected readonly partials = signal<PartialSettlement[]>([]);
  protected readonly partialsLoaded = signal(false);
  protected readonly partialShortfallTotal = computed(() =>
    this.partials().reduce((sum, p) => sum + Number(p.shortfall ?? 0), 0),
  );

  // --- Batch details drawer ----------------------------------------------
  protected readonly selectedBatch = signal<RemittanceBatch | null>(null);
  protected readonly lines = signal<RemittanceBatchLine[]>([]);
  protected readonly linesLoading = signal(false);
  /** Only show the rows that actually moved money / need attention when on. */
  protected readonly onlyActionable = signal(false);

  // --- Paging for the batch list -----------------------------------------
  protected readonly page = signal(0);
  protected readonly pageSize = signal(readPageSize('remittanceBatches', 10));
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.batches().length / this.pageSize())),
  );
  protected readonly pageItems = computed<RemittanceBatch[]>(() => {
    const start = this.page() * this.pageSize();
    return this.batches().slice(start, start + this.pageSize());
  });

  protected readonly visibleLines = computed<RemittanceBatchLine[]>(() => {
    if (!this.onlyActionable()) {
      return this.lines();
    }
    return this.lines().filter(
      (l) => l.status === 'SETTLED' || l.status === 'PARTIALLY_SETTLED' || this.isReview(l.status),
    );
  });

  ngOnInit(): void {
    this.applyPreset('this-week');
  }

  // --- Period controls ----------------------------------------------------

  protected applyPreset(key: PresetKey): void {
    this.preset.set(key);
    if (key !== 'custom') {
      const [f, t] = this.rangeFor(key);
      this.from.set(f);
      this.to.set(t);
    }
    this.load();
  }

  protected setFrom(event: Event): void {
    this.from.set((event.target as HTMLInputElement).value);
    this.preset.set('custom');
  }
  protected setTo(event: Event): void {
    this.to.set((event.target as HTMLInputElement).value);
    this.preset.set('custom');
  }
  protected applyCustom(): void {
    this.preset.set('custom');
    this.load();
  }

  protected setPageSize(size: number): void {
    this.pageSize.set(size);
    writePageSize('remittanceBatches', size);
    this.page.set(0);
  }
  protected goPage(p: number): void {
    this.page.set(Math.max(0, Math.min(p, this.totalPages() - 1)));
  }

  /** Jump to the Reconciliation page where the import modal lives. */
  protected goImport(): void {
    this.router.navigate(['/reconciliation']);
  }

  // --- Loading ------------------------------------------------------------

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    const from = this.from() || null;
    const to = this.to() || null;
    this.service.remittanceSummary(from, to).subscribe({
      next: (s) => this.summary.set(s),
      error: () => {
        /* non-fatal; the batch load sets the error if it also fails */
      },
    });
    this.service.remittanceBatches(from, to).subscribe({
      next: (rows) => {
        this.batches.set(rows);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the settlement report. Please try again.');
        this.loading.set(false);
      },
    });
    // The partial follow-up list is across all time (not period-scoped), loaded
    // once so its count badge is always accurate.
    this.loadPartials();
  }

  private loadPartials(): void {
    this.service.partiallySettled().subscribe({
      next: (rows) => {
        this.partials.set(rows);
        this.partialsLoaded.set(true);
      },
      error: () => this.partialsLoaded.set(true),
    });
  }

  protected setView(view: 'imports' | 'partial'): void {
    this.view.set(view);
  }

  /** Downloads the partial-settlement follow-up list as a CSV. */
  protected exportPartials(): void {
    const rows = this.partials();
    if (rows.length === 0) {
      return;
    }
    const header = ['Order', 'Customer', 'Tracking ID', 'Expected', 'Settled so far', 'Shortfall', 'Settled on'];
    const body = rows.map((p) => [
      p.orderCode ?? '',
      p.customerName ?? '',
      p.awb ?? '',
      this.numStr(p.expectedAmount),
      this.numStr(p.settledAmount),
      this.numStr(p.shortfall),
      p.settledDate ?? '',
    ]);
    const csv = [header, ...body].map((r) => r.map((c) => `"${String(c)}"`).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    // A live "still owed" list, so it is dated by the day it was taken.
    a.download = asOfFilename('Partial Settlements Follow-up', todayIst(), 'csv');
    a.click();
    URL.revokeObjectURL(url);
  }

  private numStr(value: number | string | null | undefined): string {
    const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[₹,\s]/g, ''));
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  // --- Batch details ------------------------------------------------------

  protected openBatch(batch: RemittanceBatch): void {
    this.selectedBatch.set(batch);
    this.onlyActionable.set(false);
    this.linesLoading.set(true);
    this.lines.set([]);
    this.service.remittanceBatchLines(batch.id).subscribe({
      next: (rows) => {
        this.lines.set(rows);
        this.linesLoading.set(false);
      },
      error: () => this.linesLoading.set(false),
    });
  }
  protected closeBatch(): void {
    this.selectedBatch.set(null);
    this.lines.set([]);
  }

  /** Downloads the open batch's per-order details as a CSV. */
  protected exportLines(): void {
    const batch = this.selectedBatch();
    const rows = this.lines();
    if (!batch || rows.length === 0) {
      return;
    }
    const header = [
      'Row', 'Order', 'Tracking ID', 'Client Order ID', 'Customer',
      'Expected', 'Remitted', 'Settled', 'Shortfall', 'Status', 'Note',
    ];
    const body = rows.map((l) => [
      l.sourceRowNumber,
      l.resolvedOrderCode ?? '',
      l.awb ?? '',
      l.clientOrderId ?? '',
      l.customerName ?? '',
      this.num(l.expectedAmount),
      this.num(l.remittedAmount),
      this.num(l.settledAmount),
      this.num(l.shortfallAmount),
      this.statusLabel(l.status),
      (l.message ?? '').replace(/"/g, "'"),
    ]);
    const csv = [header, ...body]
      .map((r) => r.map((c) => `"${String(c)}"`).join(','))
      .join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    // Dated by when the courier remitted (else when it was imported), so batches are told apart.
    const batchDate = batch.remittedDate || batch.importedAt?.slice(0, 10) || todayIst();
    a.download = reportFilename(`Remittance Batch ${batch.id} Details`, batchDate, batchDate, 'csv');
    a.click();
    URL.revokeObjectURL(url);
  }

  // --- Display helpers ----------------------------------------------------

  protected inr(value: number | string | null | undefined): string {
    return formatInr(value, 0);
  }

  private num(value: number | string | null | undefined): string {
    const n = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[₹,\s]/g, ''));
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  protected isReview(status: RemittanceRowStatus): boolean {
    return (
      status === 'MISMATCH' ||
      status === 'ORDER_NOT_FOUND' ||
      status === 'NO_RECEIVABLE' ||
      status === 'ERROR'
    );
  }

  /** Human label for a per-row status. */
  protected statusLabel(status: RemittanceRowStatus): string {
    switch (status) {
      case 'SETTLED':
        return 'Settled';
      case 'PARTIALLY_SETTLED':
        return 'Partially settled';
      case 'MISMATCH':
        return 'Amount mismatch';
      case 'ALREADY_SETTLED':
        return 'Already settled';
      case 'ORDER_NOT_FOUND':
        return 'Order not found';
      case 'NO_RECEIVABLE':
        return 'No receivable';
      case 'ERROR':
        return 'Error';
      default:
        return status;
    }
  }

  /** Brand badge tone for a per-row status. */
  protected statusTone(status: RemittanceRowStatus): string {
    switch (status) {
      case 'SETTLED':
        return 'tone-green';
      case 'PARTIALLY_SETTLED':
        return 'tone-amber';
      case 'ALREADY_SETTLED':
        return 'tone-blue';
      case 'MISMATCH':
      case 'ERROR':
        return 'tone-red';
      default:
        return 'tone-grey';
    }
  }

  /** Tabler icon for a per-row status. */
  protected statusIcon(status: RemittanceRowStatus): string {
    switch (status) {
      case 'SETTLED':
        return 'ti-circle-check-filled';
      case 'PARTIALLY_SETTLED':
        return 'ti-progress-alert';
      case 'ALREADY_SETTLED':
        return 'ti-checks';
      case 'MISMATCH':
      case 'ERROR':
        return 'ti-alert-triangle';
      default:
        return 'ti-help-circle';
    }
  }

  protected presetLabel(): string {
    switch (this.preset()) {
      case 'this-week':
        return 'This week';
      case 'last-week':
        return 'Last week';
      case 'this-month':
        return 'This month';
      case 'last-month':
        return 'Last month';
      default:
        return 'Custom range';
    }
  }

  private rangeFor(key: PresetKey): [string, string] {
    const today = todayIst();
    if (key === 'this-week' || key === 'last-week') {
      const thisMonday = weekStartIst(today);
      const monday = key === 'last-week' ? addIstDays(thisMonday, -7) : thisMonday;
      return [monday, addIstDays(monday, 6)];
    }
    if (key === 'this-month') {
      const first = monthStartIst();
      return [monthStartIst(), monthEndIst()];
    }
    // last-month
    const currentFirst = monthStartIst();
    const lastFirst = addIstDays(currentFirst, -1);
    const lastMonthFirst = `${lastFirst.slice(0, 7)}-01`;
    return [lastMonthFirst, addIstDays(currentFirst, -1)];
  }
}
