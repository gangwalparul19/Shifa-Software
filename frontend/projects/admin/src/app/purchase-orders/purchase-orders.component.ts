import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormArray,
  FormBuilder,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { ApiError, Product, SortState } from 'core';
import { CatalogService } from '../orders/catalog.service';
import { SuppliersService } from '../suppliers/suppliers.service';
import { SupplierResponse } from '../suppliers/suppliers.model';
import { PurchaseOrdersService } from './purchase-orders.service';
import {
  CreatePurchaseOrderRequest,
  PurchaseOrderItemResponse,
  PurchaseOrderResponse,
  PurchaseOrderStatus,
  PurchaseOrderSummaryResponse,
} from './purchase-orders.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { RowActionsMenuComponent, RowAction } from '../shared/row-actions-menu.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { StatusTone } from '../shared/status-badge.component';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the admin purchase-orders listing. */
const SORT_FIELDS = new Set(['createdAt', 'receivedAt', 'status', 'totalAmount', 'poNumber']);
const TABLE_KEY = 'purchase-orders';

/**
 * Admin Purchase Orders (Phase C2 — ADMIN only).
 *
 * <p>A filterable, sortable, paginated table of purchase orders (supplier and
 * status filters, PO-number search). A row opens a detail drawer with its line
 * items. Creating a PO uses a supplier + line-item form (each line picks a
 * product from the public catalog, a quantity and a unit cost, with live line
 * and grand totals). Receivable POs can be received line-by-line; cancellable
 * POs can be cancelled. Actions are gated by the PO status.
 */
@Component({
  selector: 'admin-purchase-orders',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
    RowActionsMenuComponent,
  ],
  templateUrl: './purchase-orders.component.html',
  styleUrl: './purchase-orders.component.css',
})
export class PurchaseOrdersComponent implements OnInit, OnDestroy {
  private readonly service = inject(PurchaseOrdersService);
  private readonly suppliersService = inject(SuppliersService);
  private readonly catalog = inject(CatalogService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly statusOptions: PurchaseOrderStatus[] = [
    'DRAFT',
    'ORDERED',
    'PARTIALLY_RECEIVED',
    'RECEIVED',
    'CANCELLED',
  ];

  protected readonly pos = signal<PurchaseOrderSummaryResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Summary KPIs (derived from the loaded page of purchase orders) -----
  /** Open POs awaiting any receipt. */
  protected readonly orderedCount = computed(
    () => this.pos().filter((p) => p.status === 'ORDERED').length,
  );
  /** POs partly received (some stock still outstanding). */
  protected readonly partialCount = computed(
    () => this.pos().filter((p) => p.status === 'PARTIALLY_RECEIVED').length,
  );
  /** Fully received POs on this page. */
  protected readonly receivedCount = computed(
    () => this.pos().filter((p) => p.status === 'RECEIVED').length,
  );

  // --- Reference data -----------------------------------------------------
  protected readonly suppliers = signal<SupplierResponse[]>([]);
  protected readonly products = signal<Product[]>([]);

  /** Fast id→name resolution for the table (covers inactive suppliers too). */
  private readonly supplierNames = computed(() => {
    const map = new Map<number, string>();
    for (const s of this.suppliers()) {
      map.set(s.id, s.name);
    }
    return map;
  });

  /** Active suppliers only — offered in the filter + create pickers. */
  protected readonly activeSuppliers = computed(() => this.suppliers().filter((s) => s.active));

  private readonly productNames = computed(() => {
    const map = new Map<number, string>();
    for (const p of this.products()) {
      map.set(p.id, p.name);
    }
    return map;
  });

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 10));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'createdAt', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly filters = new FormGroup({
    status: new FormControl<string>('', { nonNullable: true }),
    supplierId: new FormControl<string>('', { nonNullable: true }),
  });

  // --- Detail drawer ------------------------------------------------------
  protected readonly selectedPo = signal<PurchaseOrderResponse | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);

  // --- Create modal -------------------------------------------------------
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly createForm = this.fb.nonNullable.group({
    supplierId: ['', [Validators.required]],
    notes: ['', [Validators.maxLength(1000)]],
    items: this.fb.array<FormGroup>([]),
  });

  // --- Receive modal ------------------------------------------------------
  protected readonly receivePo = signal<PurchaseOrderResponse | null>(null);
  protected readonly receiving = signal(false);
  protected readonly receiveError = signal<string | null>(null);
  protected readonly receiveForm = this.fb.nonNullable.group({
    lines: this.fb.array<FormGroup>([]),
  });

  protected readonly cancellingId = signal<number | null>(null);

  private readonly destroy$ = new Subject<void>();

  get itemsArray(): FormArray<FormGroup> {
    return this.createForm.controls.items;
  }

  get receiveLines(): FormArray<FormGroup> {
    return this.receiveForm.controls.lines;
  }

  ngOnInit(): void {
    this.loadReferenceData();
    this.load();
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.resetAndLoad());
    this.filters.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.resetAndLoad());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private loadReferenceData(): void {
    // All suppliers (so names resolve for deactivated ones); active-only is
    // derived for the pickers.
    this.suppliersService.list(false).subscribe({
      next: (items) => this.suppliers.set(items),
      error: () => this.suppliers.set([]),
    });
    this.catalog.products().subscribe({
      next: (items) => this.products.set(items),
      error: () => this.products.set([]),
    });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        q: this.search.value,
        status: f.status || null,
        supplierId: f.supplierId ? Number(f.supplierId) : null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.pos.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load purchase orders. Please try again.');
          this.loading.set(false);
        },
      });
  }

  private resetAndLoad(): void {
    this.page.set(0);
    this.load();
  }

  goToPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  setSize(size: number): void {
    this.size.set(size);
    writePageSize(TABLE_KEY, size);
    this.resetAndLoad();
  }

  onSort(field: string): void {
    if (!SORT_FIELDS.has(field)) {
      return;
    }
    this.sort.set(toggleSort(this.sort(), field));
    this.resetAndLoad();
  }

  clearSearch(): void {
    this.search.setValue('');
  }

  clearFilters(): void {
    this.filters.reset({ status: '', supplierId: '' });
    this.search.setValue('');
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(this.search.value || f.status || f.supplierId);
  }

  // --- Presentation helpers ----------------------------------------------

  supplierName(id: number): string {
    return this.supplierNames().get(id) ?? `Supplier #${id}`;
  }

  productName(id: number): string {
    return this.productNames().get(id) ?? `Product #${id}`;
  }

  /** Maps a PO status to a shared badge tone (amber/blue/green/red/grey). */
  statusTone(status: PurchaseOrderStatus | string): StatusTone {
    switch (status) {
      case 'DRAFT':
        return 'neutral';
      case 'ORDERED':
        return 'pending';
      case 'PARTIALLY_RECEIVED':
        return 'progress';
      case 'RECEIVED':
        return 'done';
      case 'CANCELLED':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  statusLabel(status: PurchaseOrderStatus | string): string {
    return String(status)
      .replaceAll('_', ' ')
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  money(value: number | null | undefined): string {
    const n = typeof value === 'number' && Number.isFinite(value) ? value : 0;
    return `₹${n.toFixed(2)}`;
  }

  isReceivable(status: PurchaseOrderStatus | string): boolean {
    return status === 'ORDERED' || status === 'PARTIALLY_RECEIVED';
  }

  isCancellable(status: PurchaseOrderStatus | string): boolean {
    return status === 'DRAFT' || status === 'ORDERED';
  }

  /** Remaining (not-yet-received) quantity on a line. */
  remaining(item: PurchaseOrderItemResponse): number {
    return Math.max(0, item.quantity - item.receivedQuantity);
  }

  /** Status-gated per-row kebab actions mirroring the original Receive / Cancel buttons. */
  rowActions(po: PurchaseOrderSummaryResponse): RowAction[] {
    const actions: RowAction[] = [];
    if (this.isReceivable(po.status)) {
      actions.push({ key: 'receive', label: 'Receive', icon: 'ti-package-import', variant: 'primary' });
    }
    if (this.isCancellable(po.status)) {
      actions.push({
        key: 'cancel',
        label: 'Cancel',
        icon: 'ti-ban',
        variant: 'danger',
        disabled: this.cancellingId() !== null,
      });
    }
    return actions;
  }

  /** Dispatches a kebab action for the given purchase-order row. */
  onRowAction(key: string, po: PurchaseOrderSummaryResponse): void {
    if (key === 'receive') {
      this.startReceive(po);
    } else if (key === 'cancel') {
      this.cancel(po);
    }
  }

  // --- Detail drawer ------------------------------------------------------

  openDetail(po: PurchaseOrderSummaryResponse): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedPo.set(null);
    this.service.get(po.id).subscribe({
      next: (detail) => {
        this.selectedPo.set(detail);
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailError.set('Could not load this purchase order.');
        this.detailLoading.set(false);
      },
    });
  }

  closeDetail(): void {
    this.selectedPo.set(null);
    this.detailError.set(null);
  }

  // --- Create -------------------------------------------------------------

  private newItemGroup(): FormGroup {
    return this.fb.nonNullable.group({
      productId: ['', [Validators.required]],
      quantity: ['1', [Validators.required, Validators.pattern(/^\d{1,7}$/)]],
      unitCost: ['', [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    });
  }

  openCreate(): void {
    this.createError.set(null);
    this.itemsArray.clear();
    this.itemsArray.push(this.newItemGroup());
    this.createForm.reset({ supplierId: '', notes: '' });
    this.creating.set(true);
  }

  closeCreate(): void {
    this.creating.set(false);
    this.createError.set(null);
  }

  addItem(): void {
    this.itemsArray.push(this.newItemGroup());
  }

  removeItem(index: number): void {
    if (this.itemsArray.length > 1) {
      this.itemsArray.removeAt(index);
    }
  }

  lineTotal(group: FormGroup): number {
    const qty = Number(group.get('quantity')?.value);
    const cost = Number(group.get('unitCost')?.value);
    if (!Number.isFinite(qty) || !Number.isFinite(cost)) {
      return 0;
    }
    return qty * cost;
  }

  grandTotal(): number {
    return this.itemsArray.controls.reduce((sum, g) => sum + this.lineTotal(g as FormGroup), 0);
  }

  submitCreate(): void {
    if (this.saving()) {
      return;
    }
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const raw = this.createForm.getRawValue();
    const request: CreatePurchaseOrderRequest = {
      supplierId: Number(raw.supplierId),
      notes: raw.notes.trim() || null,
      items: this.itemsArray.controls.map((g) => ({
        productId: Number(g.get('productId')!.value),
        quantity: Number(g.get('quantity')!.value),
        unitCost: Number(g.get('unitCost')!.value),
      })),
    };

    this.saving.set(true);
    this.createError.set(null);
    this.service.create(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.success('Purchase order created.');
        this.closeCreate();
        this.resetAndLoad();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.createError.set(this.describeError(err));
      },
    });
  }

  // --- Receive ------------------------------------------------------------

  openReceive(po: PurchaseOrderResponse): void {
    this.receiveError.set(null);
    this.receiveLines.clear();
    for (const item of po.items) {
      const remaining = this.remaining(item);
      this.receiveLines.push(
        this.fb.nonNullable.group({
          itemId: [item.id],
          receivedQuantity: [
            String(remaining),
            [Validators.required, Validators.pattern(/^\d{1,7}$/)],
          ],
        }),
      );
    }
    this.receivePo.set(po);
  }

  /** Opens the receive form for a summary row (fetches detail first if needed). */
  startReceive(po: PurchaseOrderSummaryResponse): void {
    this.service.get(po.id).subscribe({
      next: (detail) => this.openReceive(detail),
      error: () => this.toasts.error('Could not load the purchase order to receive.'),
    });
  }

  closeReceive(): void {
    this.receivePo.set(null);
    this.receiveError.set(null);
  }

  receiveItem(index: number): PurchaseOrderItemResponse | undefined {
    return this.receivePo()?.items[index];
  }

  submitReceive(): void {
    const po = this.receivePo();
    if (!po || this.receiving()) {
      return;
    }
    if (this.receiveForm.invalid) {
      this.receiveForm.markAllAsTouched();
      return;
    }
    // Only send lines with a positive received quantity.
    const lines = this.receiveLines.controls
      .map((g) => ({
        itemId: Number(g.get('itemId')!.value),
        receivedQuantity: Number(g.get('receivedQuantity')!.value),
      }))
      .filter((l) => l.receivedQuantity > 0);

    if (lines.length === 0) {
      this.receiveError.set('Enter a received quantity on at least one line.');
      return;
    }

    this.receiving.set(true);
    this.receiveError.set(null);
    this.service.receive(po.id, { lines }).subscribe({
      next: (updated) => {
        this.receiving.set(false);
        this.toasts.success('Stock received.');
        this.closeReceive();
        // Refresh the open detail drawer if it is showing this PO.
        if (this.selectedPo()?.id === updated.id) {
          this.selectedPo.set(updated);
        }
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.receiving.set(false);
        this.receiveError.set(this.describeError(err));
      },
    });
  }

  // --- Cancel -------------------------------------------------------------

  async cancel(po: PurchaseOrderSummaryResponse | PurchaseOrderResponse): Promise<void> {
    if (this.cancellingId() !== null) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Cancel purchase order',
      message: `Cancel PO ${po.poNumber}? This cannot be undone.`,
      confirmLabel: 'Cancel PO',
      cancelLabel: 'Keep',
      danger: true,
      icon: 'ti-ban',
    });
    if (!confirmed) {
      return;
    }
    this.cancellingId.set(po.id);
    this.service.cancel(po.id).subscribe({
      next: (updated) => {
        this.cancellingId.set(null);
        this.toasts.success(`PO ${po.poNumber} cancelled.`);
        if (this.selectedPo()?.id === updated.id) {
          this.selectedPo.set(updated);
        }
        this.load();
      },
      error: () => {
        this.cancellingId.set(null);
        this.toasts.error(`Could not cancel PO ${po.poNumber}.`);
      },
    });
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not complete the request. Please try again.';
  }
}
