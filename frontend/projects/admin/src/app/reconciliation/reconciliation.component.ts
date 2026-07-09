import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, ReactiveFormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { Money, ReceivableType, SortState } from 'core';
import { ReconciliationService } from './reconciliation.service';
import {
  CourierSummary,
  ReceivableRow,
  Segregation,
  UnsettledCod,
} from './reconciliation.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the receivables listing. */
const SORT_FIELDS = new Set(['createdAt', 'amount', 'type', 'settled']);
const TABLE_KEY = 'receivables';

interface Toast {
  kind: 'ok' | 'error';
  text: string;
}

type Tab = 'receivables' | 'unsettled' | 'segregation' | 'claims';
type SettledFilter = 'all' | 'unsettled' | 'settled';

/**
 * COD and loss reconciliation dashboard (Req 17.4, 18.1-18.5).
 *
 * <p>Shows per-courier outstanding summary cards (COD receivable total, claim
 * receivable total, total outstanding), then a tabbed area with: a filterable
 * receivables table (courier, type, settled) with a Settle action; the unsettled
 * delivered-COD list; the prepaid/COD segregation; and pending claims that need
 * filing. Settling opens a confirm with an optional date, calls the settle
 * endpoint, refreshes the totals and lists, and shows a success toast.
 */
@Component({
  selector: 'admin-reconciliation',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './reconciliation.component.html',
  styleUrl: './reconciliation.component.css',
})
export class ReconciliationComponent implements OnInit, OnDestroy {
  private readonly service = inject(ReconciliationService);
  private readonly fb = inject(FormBuilder);

  protected readonly ReceivableType = ReceivableType;

  // --- Data ---------------------------------------------------------------
  protected readonly summary = signal<CourierSummary[]>([]);
  protected readonly receivables = signal<ReceivableRow[]>([]);

  /**
   * Aggregate outstanding KPI tiles across all couriers, summed from the
   * per-courier summary already loaded (no new data). COD receivable = COD
   * still to collect, claim receivable = loss claims owed, and the combined
   * total outstanding.
   */
  protected readonly totalCodOutstanding = computed(() =>
    this.sumMoney(this.summary().map((c) => c.codOutstanding)),
  );
  protected readonly totalClaimOutstanding = computed(() =>
    this.sumMoney(this.summary().map((c) => c.claimOutstanding)),
  );
  protected readonly totalOutstanding = computed(() =>
    this.sumMoney(this.summary().map((c) => c.totalOutstanding)),
  );
  protected readonly unsettled = signal<UnsettledCod[]>([]);
  protected readonly segregation = signal<Segregation | null>(null);
  protected readonly claims = signal<ReceivableRow[]>([]);

  // --- UI state -----------------------------------------------------------
  protected readonly tab = signal<Tab>('receivables');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly acting = signal(false);

  // --- Filters (receivables tab) ------------------------------------------
  protected readonly filterCourier = signal<number | null>(null);
  protected readonly filterType = signal<ReceivableType | null>(null);
  /** Default to Unsettled (outstanding) so the tab doesn't dump every settled row. */
  protected readonly filterSettled = signal<SettledFilter>('unsettled');
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly fromDate = new FormControl<string>('', { nonNullable: true });
  protected readonly toDate = new FormControl<string>('', { nonNullable: true });

  /** Whether the collapsible advanced-filter panel is open (collapsed by default). */
  protected readonly filtersOpen = signal(false);
  /** How many advanced filters deviate from their defaults, for the toggle badge. */
  protected readonly activeReceivableFilterCount = signal(0);

  /** Show/hide the advanced-filter panel. */
  toggleFilters(): void {
    this.filtersOpen.update((open) => !open);
  }

  // --- Receivables paging + sort ------------------------------------------
  protected readonly recvPage = signal(0);
  protected readonly recvSize = signal(readPageSize(TABLE_KEY, 10));
  protected readonly recvTotalPages = signal(0);
  protected readonly recvTotalElements = signal(0);
  protected readonly recvSort = signal<SortState>({ field: 'createdAt', dir: 'desc' });

  private readonly destroy$ = new Subject<void>();

  // --- Settle modal -------------------------------------------------------
  protected readonly settleTarget = signal<ReceivableRow | UnsettledCod | null>(null);
  protected readonly settleForm = this.fb.nonNullable.group({
    date: [this.today()],
  });

  private toastTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.loadAll();

    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.reloadReceivables());

    this.fromDate.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.reloadReceivables());
    this.toDate.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.reloadReceivables());

    this.updateReceivableFilterCount();
  }

  ngOnDestroy(): void {
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.destroy$.next();
    this.destroy$.complete();
  }

  // --- Loading ------------------------------------------------------------

  loadAll(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.refreshSummary();
    this.refreshReceivables();
    this.refreshUnsettled();
    this.refreshSegregation();
    this.refreshClaims(() => {
      this.loading.set(false);
    });
  }

  private refreshSummary(done?: () => void): void {
    this.service.summary().subscribe({
      next: (s) => {
        this.summary.set(s);
        done?.();
      },
      error: () => {
        this.loadError.set('Could not load reconciliation data. Please try again.');
        done?.();
      },
    });
  }

  private refreshReceivables(done?: () => void): void {
    const settled = this.filterSettled();
    this.service
      .receivablesPage({
        courier: this.filterCourier(),
        type: this.filterType(),
        settled: settled === 'all' ? null : settled === 'settled',
        q: this.search.value,
        from: this.fromDate.value || null,
        to: this.toDate.value || null,
        page: this.recvPage(),
        size: this.recvSize(),
        sort: sortParam(this.recvSort()),
      })
      .subscribe({
        next: (res) => {
          this.receivables.set(res.content);
          this.recvTotalPages.set(res.totalPages);
          this.recvTotalElements.set(res.totalElements);
          done?.();
        },
        error: () => done?.(),
      });
  }

  /** Reset the receivables list to page 0 and reload (on any filter/sort change). */
  private reloadReceivables(): void {
    this.recvPage.set(0);
    this.updateReceivableFilterCount();
    this.refreshReceivables();
  }

  /** Recomputes how many advanced filters deviate from their defaults (badge). */
  private updateReceivableFilterCount(): void {
    let count = 0;
    if (this.filterCourier() != null) count++;
    if (this.filterType()) count++;
    if (this.filterSettled() !== 'unsettled') count++;
    if (this.fromDate.value) count++;
    if (this.toDate.value) count++;
    this.activeReceivableFilterCount.set(count);
  }

  goToReceivablesPage(page: number): void {
    this.recvPage.set(page);
    this.refreshReceivables();
  }

  setReceivablesSize(size: number): void {
    this.recvSize.set(size);
    writePageSize(TABLE_KEY, size);
    this.reloadReceivables();
  }

  onReceivablesSort(field: string): void {
    if (!SORT_FIELDS.has(field)) {
      return;
    }
    this.recvSort.set(toggleSort(this.recvSort(), field));
    this.reloadReceivables();
  }

  clearReceivableFilters(): void {
    this.filterCourier.set(null);
    this.filterType.set(null);
    this.filterSettled.set('unsettled');
    this.search.setValue('', { emitEvent: false });
    this.fromDate.setValue('', { emitEvent: false });
    this.toDate.setValue('', { emitEvent: false });
    this.reloadReceivables();
  }

  hasReceivableFilters(): boolean {
    return !!(
      this.filterCourier() != null ||
      this.filterType() ||
      this.filterSettled() !== 'unsettled' ||
      this.search.value ||
      this.fromDate.value ||
      this.toDate.value
    );
  }

  private refreshUnsettled(done?: () => void): void {
    this.service.unsettledCod().subscribe({
      next: (rows) => {
        this.unsettled.set(rows);
        done?.();
      },
      error: () => done?.(),
    });
  }

  private refreshSegregation(done?: () => void): void {
    this.service.segregation().subscribe({
      next: (s) => {
        this.segregation.set(s);
        done?.();
      },
      error: () => done?.(),
    });
  }

  private refreshClaims(done?: () => void): void {
    this.service.pendingClaims().subscribe({
      next: (rows) => {
        this.claims.set(rows);
        done?.();
      },
      error: () => done?.(),
    });
  }

  // --- Filters ------------------------------------------------------------

  onCourierFilter(value: string): void {
    this.filterCourier.set(value === '' ? null : Number(value));
    this.reloadReceivables();
  }

  onTypeFilter(value: string): void {
    this.filterType.set(value === '' ? null : (value as ReceivableType));
    this.reloadReceivables();
  }

  onSettledFilter(value: string): void {
    this.filterSettled.set(value as SettledFilter);
    this.reloadReceivables();
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
  }

  // --- Settle -------------------------------------------------------------

  openSettle(target: ReceivableRow | UnsettledCod): void {
    this.settleForm.reset({ date: this.today() });
    this.settleTarget.set(target);
  }

  cancelSettle(): void {
    this.settleTarget.set(null);
  }

  confirmSettle(): void {
    const target = this.settleTarget();
    if (!target || this.acting()) {
      return;
    }
    const id = this.receivableId(target);
    const code = this.orderCodeOf(target);
    const date = this.settleForm.getRawValue().date || null;
    this.acting.set(true);
    this.service.settle(id, date).subscribe({
      next: () => {
        this.acting.set(false);
        this.settleTarget.set(null);
        this.showToast('ok', `Receivable for ${code ?? 'order'} settled.`);
        // Reflect the reduced outstanding + updated lists (Req 18.5).
        this.refreshSummary();
        this.refreshReceivables();
        this.refreshUnsettled();
        this.refreshClaims();
      },
      error: () => {
        this.acting.set(false);
        this.showToast('error', `Could not settle receivable for ${code ?? 'order'}.`);
      },
    });
  }

  private receivableId(target: ReceivableRow | UnsettledCod): number {
    return 'receivableId' in target ? target.receivableId : target.id;
  }

  private orderCodeOf(target: ReceivableRow | UnsettledCod): string | null {
    return target.orderCode ?? null;
  }

  // --- Helpers ------------------------------------------------------------

  money(value: Money | undefined | null): string {
    if (value === undefined || value === null) {
      return '₹0.00';
    }
    return `₹${value}`;
  }

  /** Sums a list of Money (decimal-string) values into a formatted ₹ total. */
  private sumMoney(values: (Money | undefined | null)[]): string {
    const total = values.reduce<number>((acc, v) => acc + Number(v ?? 0), 0);
    return `₹${total.toFixed(2)}`;
  }

  courierLabel(name: string | null): string {
    return name ?? 'Unassigned';
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private showToast(kind: Toast['kind'], text: string): void {
    this.toast.set({ kind, text });
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), 4000);
  }
}
