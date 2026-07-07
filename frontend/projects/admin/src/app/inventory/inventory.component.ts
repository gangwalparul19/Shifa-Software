import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError, StockStatus } from 'core';
import {
  AdjustRequest,
  InventoryProduct,
  InventoryService,
  RestockRequest,
  StockMovement,
} from './inventory.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/** Which modal (if any) is currently open. */
type ActiveModal = 'restock' | 'adjust' | 'history' | null;

/** Adjustment direction; combined with a magnitude to build a signed delta. */
type AdjustDirection = 'add' | 'remove';

/** Preset adjustment reasons offered in the Adjust modal's dropdown. */
const ADJUST_REASONS = [
  'Stock-take correction',
  'Damaged / expired',
  'Customer return',
  'Lost / shrinkage',
  'Other',
];

/**
 * Admin inventory / stock-management page (Wave 3, Feature 1).
 *
 * <p>Lists tracked products with their current stock and a stock-status badge,
 * supports a "Low stock only" toggle (backed by the {@code /low-stock}
 * endpoint) and a text filter, and offers per-row Restock / Adjust actions plus
 * a stock-movement History view. Restock/Adjust confirm via {@link
 * ConfirmService} and report via {@link ToastService}, refreshing the affected
 * row on success. The list endpoints are un-paginated, so filtering happens
 * client-side.
 */
@Component({
  selector: 'admin-inventory',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
  ],
  templateUrl: './inventory.component.html',
  styleUrl: './inventory.component.css',
})
export class InventoryComponent implements OnInit {
  private readonly service = inject(InventoryService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly Stock = StockStatus;
  protected readonly adjustReasons = ADJUST_REASONS;

  protected readonly items = signal<InventoryProduct[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** When true, only low-stock (and optionally out-of-stock) rows are loaded. */
  protected readonly lowStockOnly = signal(false);
  /** When low-stock filtering, whether to also include out-of-stock rows. */
  protected readonly includeOutOfStock = signal(true);

  /** Free-text filter over SKU / name (client-side). */
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  private readonly searchTerm = signal('');

  /** The filtered rows shown in the table. */
  protected readonly visibleItems = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    const rows = this.items();
    if (!term) {
      return rows;
    }
    return rows.filter(
      (r) => r.name.toLowerCase().includes(term) || r.sku.toLowerCase().includes(term),
    );
  });

  protected readonly lowStockCount = computed(
    () => this.items().filter((r) => r.stockStatus !== StockStatus.IN_STOCK && r.trackInventory).length,
  );

  // --- Modal state --------------------------------------------------------
  protected readonly modal = signal<ActiveModal>(null);
  protected readonly activeProduct = signal<InventoryProduct | null>(null);
  protected readonly submitting = signal(false);
  protected readonly modalError = signal<string | null>(null);

  // Restock form
  protected readonly restockQty = new FormControl<number | null>(null, {
    validators: [Validators.required, Validators.min(1)],
  });
  protected readonly restockNote = new FormControl<string>('', { nonNullable: true });

  // Adjust form
  protected readonly adjustDirection = signal<AdjustDirection>('add');
  protected readonly adjustQty = new FormControl<number | null>(null, {
    validators: [Validators.required, Validators.min(1)],
  });
  protected readonly adjustReason = new FormControl<string>(ADJUST_REASONS[0], { nonNullable: true });
  protected readonly adjustNote = new FormControl<string>('', { nonNullable: true });

  // History state
  protected readonly movements = signal<StockMovement[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly historyError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const source$ = this.lowStockOnly()
      ? this.service.lowStock(this.includeOutOfStock())
      : this.service.list();
    source$.subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load inventory. Please try again.');
        this.loading.set(false);
      },
    });
  }

  applySearch(): void {
    this.searchTerm.set(this.search.value);
  }

  clearSearch(): void {
    this.search.setValue('');
    this.searchTerm.set('');
  }

  toggleLowStock(): void {
    this.lowStockOnly.update((v) => !v);
    this.load();
  }

  toggleIncludeOutOfStock(): void {
    this.includeOutOfStock.update((v) => !v);
    if (this.lowStockOnly()) {
      this.load();
    }
  }

  // --- Badge tones (reusing the shared status-badge tone language) --------

  /** Maps a stock status to a shared badge tone (done/pending/bad). */
  tone(row: InventoryProduct): 'done' | 'pending' | 'bad' | 'neutral' {
    if (!row.trackInventory) {
      return 'neutral';
    }
    switch (row.stockStatus) {
      case StockStatus.OUT_OF_STOCK:
        return 'bad';
      case StockStatus.LOW_STOCK:
        return 'pending';
      default:
        return 'done';
    }
  }

  stockLabel(row: InventoryProduct): string {
    if (!row.trackInventory) {
      return 'Not tracked';
    }
    switch (row.stockStatus) {
      case StockStatus.OUT_OF_STOCK:
        return 'Out of stock';
      case StockStatus.LOW_STOCK:
        return 'Low stock';
      default:
        return 'In stock';
    }
  }

  // --- Restock modal ------------------------------------------------------

  openRestock(row: InventoryProduct): void {
    this.activeProduct.set(row);
    this.modalError.set(null);
    this.restockQty.reset(null);
    this.restockNote.setValue('');
    this.modal.set('restock');
  }

  async submitRestock(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    if (this.restockQty.invalid) {
      this.restockQty.markAsTouched();
      return;
    }
    const row = this.activeProduct();
    if (!row) {
      return;
    }
    const quantity = Number(this.restockQty.value);
    const confirmed = await this.confirm.confirm({
      title: 'Restock product',
      message: `Add ${quantity} unit${quantity === 1 ? '' : 's'} to "${row.name}"? New on-hand will be ${row.stockQuantity + quantity}.`,
      confirmLabel: 'Restock',
      icon: 'ti-package-import',
    });
    if (!confirmed) {
      return;
    }
    const request: RestockRequest = {
      quantity,
      reason: this.restockNote.value.trim() || null,
    };
    this.submitting.set(true);
    this.modalError.set(null);
    this.service.restock(row.id, request).subscribe({
      next: (updated) => {
        this.submitting.set(false);
        this.applyRowUpdate(updated);
        this.closeModal();
        this.toasts.success(`Restocked ${row.name} (+${quantity}).`);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.modalError.set(this.describeError(err));
      },
    });
  }

  // --- Adjust modal -------------------------------------------------------

  openAdjust(row: InventoryProduct): void {
    this.activeProduct.set(row);
    this.modalError.set(null);
    this.adjustDirection.set('add');
    this.adjustQty.reset(null);
    this.adjustReason.setValue(ADJUST_REASONS[0]);
    this.adjustNote.setValue('');
    this.modal.set('adjust');
  }

  setDirection(direction: AdjustDirection): void {
    this.adjustDirection.set(direction);
  }

  /** The signed delta implied by the current direction + magnitude. */
  protected readonly adjustDelta = computed(() => {
    const qty = Number(this.adjustQtyValue());
    if (!qty || Number.isNaN(qty)) {
      return 0;
    }
    return this.adjustDirection() === 'remove' ? -qty : qty;
  });

  /** Signal mirror of the adjust quantity control for the computed delta. */
  private readonly adjustQtyValue = signal<number | null>(null);

  onAdjustQtyInput(): void {
    this.adjustQtyValue.set(this.adjustQty.value);
  }

  async submitAdjust(): Promise<void> {
    if (this.submitting()) {
      return;
    }
    if (this.adjustQty.invalid) {
      this.adjustQty.markAsTouched();
      return;
    }
    const row = this.activeProduct();
    if (!row) {
      return;
    }
    const magnitude = Number(this.adjustQty.value);
    const delta = this.adjustDirection() === 'remove' ? -magnitude : magnitude;
    if (delta === 0) {
      this.modalError.set('Adjustment must not be zero.');
      return;
    }
    const projected = row.stockQuantity + delta;
    if (projected < 0) {
      this.modalError.set(`This would drop stock below zero (current ${row.stockQuantity}).`);
      return;
    }
    const reason = this.buildAdjustReason();
    const confirmed = await this.confirm.confirm({
      title: 'Adjust stock',
      message: `Apply ${delta > 0 ? '+' : ''}${delta} to "${row.name}"? New on-hand will be ${projected}.`,
      confirmLabel: 'Apply adjustment',
      danger: delta < 0,
      icon: 'ti-adjustments',
    });
    if (!confirmed) {
      return;
    }
    const request: AdjustRequest = { delta, reason: reason || null };
    this.submitting.set(true);
    this.modalError.set(null);
    this.service.adjust(row.id, request).subscribe({
      next: (updated) => {
        this.submitting.set(false);
        this.applyRowUpdate(updated);
        this.closeModal();
        this.toasts.success(`Adjusted ${row.name} (${delta > 0 ? '+' : ''}${delta}).`);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.modalError.set(this.describeError(err));
      },
    });
  }

  /** Combines the reason dropdown with the optional note into one string. */
  private buildAdjustReason(): string {
    const reason = this.adjustReason.value;
    const note = this.adjustNote.value.trim();
    if (reason === 'Other') {
      return note;
    }
    return note ? `${reason}: ${note}` : reason;
  }

  // --- History modal ------------------------------------------------------

  openHistory(row: InventoryProduct): void {
    this.activeProduct.set(row);
    this.movements.set([]);
    this.historyError.set(null);
    this.modal.set('history');
    this.historyLoading.set(true);
    this.service.movements(row.id).subscribe({
      next: (rows) => {
        this.movements.set(rows);
        this.historyLoading.set(false);
      },
      error: () => {
        this.historyError.set('Could not load stock movements.');
        this.historyLoading.set(false);
      },
    });
  }

  /** A friendly label for the acting user id (or "System" for null). */
  actorLabel(movement: StockMovement): string {
    return movement.createdBy != null ? `User #${movement.createdBy}` : 'System';
  }

  // --- Modal helpers ------------------------------------------------------

  closeModal(): void {
    this.modal.set(null);
    this.activeProduct.set(null);
    this.modalError.set(null);
    this.submitting.set(false);
  }

  private applyRowUpdate(updated: InventoryProduct): void {
    this.items.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
    // If low-stock filtering and the row is now healthy, drop it from the view
    // on the next load; keep it visible until then to avoid a jarring jump.
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not complete the operation. Please try again.';
  }
}
