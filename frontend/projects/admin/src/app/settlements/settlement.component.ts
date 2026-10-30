import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiError } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { SettingsService, PaymentAccountOption } from '../settings/settings.service';
import { SettlementService } from './settlement.service';
import {
  SettlementBatch,
  SettlementImportResponse,
  SettlementLine,
  SettlementKpi,
  SettlementMatchStatus,
  SettlementSource,
} from './settlement.model';

const PAGE_SIZE = 10;
const SOURCES: { value: SettlementSource; label: string; hint: string }[] = [
  { value: 'BANK_RECEIPT', label: 'Portal / bank receipt', hint: 'Match by UTR and receiving account.' },
  { value: 'SHOPIFY_PAYOUT', label: 'Shopify payout', hint: 'Match by Shopify order ID.' },
  { value: 'QUICKSHIPX_COD', label: 'QuikShip COD batch', hint: 'Match by AWB/order code; existing remittance import remains available.' },
];

@Component({
  selector: 'admin-settlement-center',
  imports: [PageHeaderComponent, PaginationComponent, StatePanelComponent],
  templateUrl: './settlement.component.html',
  styleUrl: './settlement.component.css',
})
export class SettlementComponent implements OnInit {
  private readonly service = inject(SettlementService);
  private readonly settings = inject(SettingsService);

  protected readonly sources = SOURCES;
  protected readonly accounts = signal<PaymentAccountOption[]>([]);
  protected readonly batches = signal<SettlementBatch[]>([]);
  protected readonly lines = signal<SettlementLine[]>([]);
  protected readonly selectedBatch = signal<SettlementBatch | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly kpi = signal<SettlementKpi | null>(null);
  protected readonly kpiLoading = signal(true);
  protected readonly kpiError = signal<string | null>(null);
  protected readonly kpiFrom = signal(this.todayIst());
  protected readonly kpiTo = signal(this.todayIst());
  protected readonly kpiPreset = signal('TODAY');
  protected readonly kpiPeriods = [
    { key: 'TODAY', label: 'Today' },
    { key: 'YESTERDAY', label: 'Yesterday' },
    { key: 'WEEK', label: 'This week' },
    { key: 'MONTH', label: 'This month' },
    { key: 'ALL', label: 'All time' },
  ];
  protected readonly busy = signal(false);
  protected readonly source = signal<SettlementSource>('BANK_RECEIPT');
  protected readonly file = signal<File | null>(null);
  protected readonly reference = signal('');
  protected readonly statementDate = signal(new Date().toISOString().slice(0, 10));
  protected readonly accountId = signal<number | null>(null);
  protected readonly preview = signal<SettlementImportResponse | null>(null);
  protected readonly result = signal<SettlementImportResponse | null>(null);
  protected readonly page = signal(0);
  protected readonly pageItems = computed(() => this.batches().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.batches().length / PAGE_SIZE)));

  ngOnInit(): void {
    this.load();
    this.loadKpis();
    this.settings.activePaymentAccounts().subscribe({
      next: (accounts) => this.accounts.set(accounts),
      error: () => this.accounts.set([]),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.batches().subscribe({
      next: (rows) => { this.batches.set(rows); this.page.set(0); this.loading.set(false); },
      error: () => { this.error.set('Could not load settlement batches.'); this.loading.set(false); },
    });
  }

  protected loadKpis(): void {
    this.kpiLoading.set(true);
    this.kpiError.set(null);
    this.service.kpis(this.kpiFrom(), this.kpiTo(), this.kpiPreset() === 'ALL').subscribe({
      next: (value) => { this.kpi.set(value); this.kpiLoading.set(false); },
      error: () => { this.kpiError.set('Could not load account-wise payment totals.'); this.kpiLoading.set(false); },
    });
  }

  protected applyKpiPeriod(key: string): void {
    const today = this.todayIst();
    let from = today;
    let to = today;
    if (key === 'YESTERDAY') {
      from = this.addDays(today, -1); to = from;
    } else if (key === 'WEEK') {
      const day = new Date(`${today}T12:00:00`).getDay();
      const mondayOffset = day === 0 ? -6 : 1 - day;
      from = this.addDays(today, mondayOffset);
    } else if (key === 'MONTH') {
      from = `${today.slice(0, 8)}01`;
    } else if (key === 'ALL') {
      from = ''; to = '';
    }
    this.kpiPreset.set(key);
    this.kpiFrom.set(from);
    this.kpiTo.set(to);
    this.loadKpis();
  }

  protected setKpiDate(which: 'from' | 'to', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.kpiPreset.set('CUSTOM');
    if (which === 'from') this.kpiFrom.set(value);
    else this.kpiTo.set(value);
    this.loadKpis();
  }

  protected kpiMoney(value: unknown): string { return this.money(value); }

  private todayIst(): string {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
    }).formatToParts(new Date());
    const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '';
    return `${get('year')}-${get('month')}-${get('day')}`;
  }

  private addDays(iso: string, days: number): string {
    const date = new Date(`${iso}T12:00:00`);
    date.setDate(date.getDate() + days);
    return date.toISOString().slice(0, 10);
  }

  protected onFile(event: Event): void {
    this.file.set((event.target as HTMLInputElement).files?.[0] ?? null);
    this.preview.set(null);
    this.result.set(null);
  }
  protected setSource(source: SettlementSource): void {
    this.source.set(source);
    this.preview.set(null);
    this.result.set(null);
    if (source === 'BANK_RECEIPT' && this.accounts().length === 1) {
      this.accountId.set(this.accounts()[0].id);
    }
  }
  protected setAccount(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.accountId.set(value ? Number(value) : null);
  }
  protected setReference(event: Event): void { this.reference.set((event.target as HTMLInputElement).value); }
  protected setDate(event: Event): void { this.statementDate.set((event.target as HTMLInputElement).value); }

  protected previewImport(): void { this.runImport(true); }
  protected commitImport(): void { this.runImport(false); }
  private runImport(dryRun: boolean): void {
    const file = this.file();
    if (!file || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.service.importFile(file, this.source(), dryRun, this.accountId(), this.reference(), this.statementDate()).subscribe({
      next: (response) => {
        if (dryRun) this.preview.set(response);
        else { this.result.set(response); this.load(); }
        this.busy.set(false);
      },
      error: (err: HttpErrorResponse) => { this.busy.set(false); this.error.set(this.errorMessage(err)); },
    });
  }

  protected openBatch(batch: SettlementBatch): void {
    this.selectedBatch.set(batch);
    this.service.lines(batch.id).subscribe({
      next: (rows) => this.lines.set(rows),
      error: () => this.lines.set([]),
    });
  }
  protected closeBatch(): void { this.selectedBatch.set(null); this.lines.set([]); }
  protected goPage(page: number): void { this.page.set(Math.max(0, Math.min(page, this.totalPages() - 1))); }
  protected sourceLabel(source: SettlementSource): string { return SOURCES.find((item) => item.value === source)?.label ?? source; }
  protected sourceHint(): string { return SOURCES.find((item) => item.value === this.source())?.hint ?? ''; }
  protected statusLabel(status: string): string { return status.replaceAll('_', ' '); }
  protected money(value: unknown): string { return value == null ? '₹0.00' : `₹${value}`; }
  protected positive(value: unknown): boolean { return Number(value ?? 0) > 0; }
  protected count(response: SettlementImportResponse | null, status: SettlementMatchStatus): number { return response?.counts?.[status] ?? 0; }
  private errorMessage(err: HttpErrorResponse): string { return (err.error as ApiError | undefined)?.message ?? 'Could not import the settlement file.'; }
}
