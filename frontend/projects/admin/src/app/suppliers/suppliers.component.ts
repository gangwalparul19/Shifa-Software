import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from 'core';
import { SupplierRequest, SupplierResponse } from './suppliers.model';
import { SuppliersService } from './suppliers.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { RowActionsMenuComponent, RowAction } from '../shared/row-actions-menu.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/**
 * Admin Suppliers management (Phase C2 — ADMIN only).
 *
 * <p>Lists suppliers from the array-returning {@code GET /api/admin/suppliers}
 * with a client-side name/contact filter and an {@code activeOnly} toggle.
 * New/edit is handled through a reactive-form modal; activate/deactivate are
 * inline actions (deactivate confirms first). All mutations refresh the list.
 */
@Component({
  selector: 'admin-suppliers',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    DensityToggleComponent,
    RowActionsMenuComponent,
  ],
  templateUrl: './suppliers.component.html',
  styleUrl: './suppliers.component.css',
})
export class SuppliersComponent implements OnInit {
  private readonly service = inject(SuppliersService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly suppliers = signal<SupplierResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly togglingId = signal<number | null>(null);

  // --- Filters ------------------------------------------------------------
  protected readonly search = signal('');
  protected readonly activeOnly = signal(false);

  /** Client-side filtered view of the loaded suppliers. */
  protected readonly filtered = computed<SupplierResponse[]>(() => {
    const q = this.search().trim().toLowerCase();
    if (!q) {
      return this.suppliers();
    }
    return this.suppliers().filter((s) =>
      [s.name, s.contactPerson, s.phone, s.email]
        .filter((v): v is string => !!v)
        .some((v) => v.toLowerCase().includes(q)),
    );
  });

  // --- Client-side paging (over the filtered list) ------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('suppliers', 10));
  protected readonly totalElements = computed(() => this.filtered().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed<SupplierResponse[]>(() => {
    const s = this.page() * this.size();
    return this.filtered().slice(s, s + this.size());
  });

  // --- Add / edit form ---------------------------------------------------
  protected readonly editing = signal<SupplierResponse | null>(null);
  protected readonly creating = signal(false);
  protected readonly formOpen = computed(() => this.creating() || this.editing() !== null);
  protected readonly formError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(150)]],
    contactPerson: ['', [Validators.maxLength(150)]],
    phone: ['', [Validators.maxLength(30)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    address: ['', [Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list(this.activeOnly()).subscribe({
      next: (items) => {
        this.suppliers.set(items);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load suppliers. Please try again.');
        this.loading.set(false);
      },
    });
  }

  onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
  }

  clearSearch(): void {
    this.search.set('');
    this.page.set(0);
  }

  // --- Paging handlers ----------------------------------------------------
  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('suppliers', s);
    this.page.set(0);
  }

  toggleActiveOnly(value: boolean): void {
    this.activeOnly.set(value);
    this.load();
  }

  // --- Add / edit form ---------------------------------------------------

  openCreate(): void {
    this.formError.set(null);
    this.editing.set(null);
    this.form.reset({ name: '', contactPerson: '', phone: '', email: '', address: '' });
    this.creating.set(true);
  }

  openEdit(supplier: SupplierResponse): void {
    this.formError.set(null);
    this.creating.set(false);
    this.form.reset({
      name: supplier.name,
      contactPerson: supplier.contactPerson ?? '',
      phone: supplier.phone ?? '',
      email: supplier.email ?? '',
      address: supplier.address ?? '',
    });
    this.editing.set(supplier);
  }

  closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
    this.formError.set(null);
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const request: SupplierRequest = {
      name: raw.name.trim(),
      contactPerson: raw.contactPerson.trim() || null,
      phone: raw.phone.trim() || null,
      email: raw.email.trim() || null,
      address: raw.address.trim() || null,
    };

    this.saving.set(true);
    this.formError.set(null);
    const editing = this.editing();
    const op$ = editing ? this.service.update(editing.id, request) : this.service.create(request);
    op$.subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.success(editing ? 'Supplier updated.' : 'Supplier created.');
        this.closeForm();
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  /** Per-row kebab actions mirroring the original Edit / (de)activate buttons. */
  rowActions(supplier: SupplierResponse): RowAction[] {
    return [
      { key: 'edit', label: 'Edit', icon: 'ti-edit' },
      {
        key: 'toggle',
        label: supplier.active ? 'Deactivate' : 'Activate',
        icon: supplier.active ? 'ti-building-off' : 'ti-building',
        variant: supplier.active ? 'danger' : 'success',
        disabled: this.togglingId() !== null,
      },
    ];
  }

  /** Dispatches a kebab action for the given supplier row. */
  onRowAction(key: string, supplier: SupplierResponse): void {
    if (key === 'edit') {
      this.openEdit(supplier);
    } else if (key === 'toggle') {
      this.toggleActive(supplier);
    }
  }

  // --- Activate / deactivate ---------------------------------------------

  async toggleActive(supplier: SupplierResponse): Promise<void> {
    if (this.togglingId() !== null) {
      return;
    }
    if (supplier.active) {
      const confirmed = await this.confirmService.confirm({
        title: 'Deactivate supplier',
        message: `Deactivate "${supplier.name}"? They will no longer appear when creating purchase orders.`,
        confirmLabel: 'Deactivate',
        danger: true,
        icon: 'ti-building-off',
      });
      if (!confirmed) {
        return;
      }
    }
    this.togglingId.set(supplier.id);
    const op$ = supplier.active
      ? this.service.deactivate(supplier.id)
      : this.service.activate(supplier.id);
    op$.subscribe({
      next: (updated) => {
        this.suppliers.update((items) => {
          const next = items.map((s) => (s.id === updated.id ? updated : s));
          // Drop it from the active-only view when it was just deactivated.
          return this.activeOnly() ? next.filter((s) => s.active) : next;
        });
        this.togglingId.set(null);
        this.toasts.success(
          updated.active ? `${supplier.name} activated.` : `${supplier.name} deactivated.`,
        );
      },
      error: () => {
        this.togglingId.set(null);
        this.toasts.error(`Could not update ${supplier.name}.`);
      },
    });
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not save. Please try again.';
  }
}
