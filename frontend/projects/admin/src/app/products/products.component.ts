import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import {
  ApiError,
  Category,
  Money,
  Product,
  ProductVisibility,
  SortState,
  StockStatus,
  stockBadgeLabel,
} from 'core';
import { ImportResult, ProductRequest, ProductsService } from './products.service';
import { CategoriesService, CategoryRequest } from './categories.service';
import { SettingsService } from '../settings/settings.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the admin products listing. */
const SORT_FIELDS = new Set(['name', 'sku', 'salePrice', 'mrp', 'stockQuantity', 'createdAt']);
const TABLE_KEY = 'products';

/**
 * Admin product management grid (Req 6.1-6.4 + Catalog & Discovery).
 *
 * <p>Lists ALL products — published and hidden. An admin can add/edit a product
 * (including its category, stock quantity, inventory tracking and featured
 * flag), quickly toggle visibility, and manage categories inline. The table
 * shows category and derived stock status. A duplicate-SKU conflict (409) is
 * shown inline on the form.
 */
@Component({
  selector: 'admin-products',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './products.component.html',
  styleUrl: './products.component.css',
})
export class ProductsComponent implements OnInit, OnDestroy {
  private readonly service = inject(ProductsService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly settingsService = inject(SettingsService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly Visibility = ProductVisibility;
  protected readonly Stock = StockStatus;
  protected readonly stockOptions = Object.values(StockStatus);

  protected readonly products = signal<Product[]>([]);
  protected readonly categories = signal<Category[]>([]);
  /**
   * Configured GST slabs from settings (e.g. [0, 5, 12, 18, 28]), offered as a
   * datalist on the per-product GST rate field. Empty when settings are
   * unavailable — the field stays free-entry.
   */
  protected readonly gstSlabs = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly togglingId = signal<number | null>(null);

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 20));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'name', dir: 'asc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly filters = new FormGroup({
    category: new FormControl<string>('', { nonNullable: true }),
    visibility: new FormControl<string>('', { nonNullable: true }),
    stockStatus: new FormControl<string>('', { nonNullable: true }),
  });

  private readonly destroy$ = new Subject<void>();

  /** The product being edited (form open); null when the form is closed. */
  protected readonly editing = signal<Product | null>(null);
  /** True when the form is open for a brand-new product. */
  protected readonly creating = signal(false);
  protected readonly formOpen = computed(() => this.creating() || this.editing() !== null);
  /** A server-side error (e.g. duplicate SKU) shown at the top of the form. */
  protected readonly formError = signal<string | null>(null);

  // --- Categories management panel state ---------------------------------
  protected readonly categoriesOpen = signal(false);
  protected readonly editingCategory = signal<Category | null>(null);
  protected readonly categorySaving = signal(false);
  protected readonly categoryFormError = signal<string | null>(null);

  // --- CSV import state (Set B — Feature 5) ------------------------------
  /** The expected CSV columns, shown as a hint (sku/name/mrp/salePrice required). */
  protected readonly importColumns =
    'sku,name,mrp,salePrice,hsnCode,gstRate,stockQuantity,trackInventory,category,visibility,description';
  protected readonly importOpen = signal(false);
  protected readonly importFile = signal<File | null>(null);
  protected readonly importBusy = signal(false);
  protected readonly importError = signal<string | null>(null);
  /** The dry-run preview result (null until a file has been previewed). */
  protected readonly importPreview = signal<ImportResult | null>(null);
  /** The committed import result (null until the import is confirmed). */
  protected readonly importResult = signal<ImportResult | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    sku: ['', [Validators.required, Validators.maxLength(64)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
    mrp: ['', [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    salePrice: ['', [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)]],
    hsnCode: ['', [Validators.maxLength(20)]],
    gstRate: ['', [Validators.pattern(/^\d{1,3}(\.\d{1,2})?$/)]],
    visibility: [ProductVisibility.PUBLISHED, [Validators.required]],
    categoryId: [''],
    stockQuantity: ['0', [Validators.pattern(/^\d{1,7}$/)]],
    trackInventory: [false],
    featured: [false],
  });

  protected readonly categoryForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    slug: ['', [Validators.maxLength(140)]],
    description: ['', [Validators.maxLength(500)]],
    sortOrder: ['0', [Validators.pattern(/^\d{1,4}$/)]],
    active: [true],
  });

  ngOnInit(): void {
    this.load();
    this.loadCategories();
    this.loadGstSlabs();

    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.resetAndLoad());

    this.filters.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.resetAndLoad());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        q: this.search.value,
        category: f.category || null,
        visibility: f.visibility || null,
        stockStatus: f.stockStatus || null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.products.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load products. Please try again.');
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
    this.filters.reset({ category: '', visibility: '', stockStatus: '' });
    this.search.setValue('');
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(this.search.value || f.category || f.visibility || f.stockStatus);
  }

  humanizeStock(status: string): string {
    return status
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }

  loadCategories(): void {
    this.categoriesService.list().subscribe({
      next: (items) => this.categories.set(items),
      error: () => this.categories.set([]),
    });
  }

  /** Loads the configured GST slabs to offer as a datalist on the rate field. */
  private loadGstSlabs(): void {
    this.settingsService.get().subscribe({
      next: (s) => {
        const slabs = (s.gstSlabs ?? '')
          .split(',')
          .map((v) => v.trim())
          .filter((v) => v.length > 0);
        this.gstSlabs.set(slabs);
      },
      error: () => this.gstSlabs.set([]),
    });
  }

  money(value: Money | undefined): string {
    if (value === undefined || value === null) {
      return '₹0.00';
    }
    return `₹${value}`;
  }

  categoryName(product: Product): string {
    return product.category?.name ?? '—';
  }

  stockLabel(product: Product): string {
    return stockBadgeLabel(product);
  }

  stockTone(product: Product): 'ok' | 'low' | 'out' {
    switch (product.stockStatus) {
      case StockStatus.OUT_OF_STOCK:
        return 'out';
      case StockStatus.LOW_STOCK:
        return 'low';
      default:
        return 'ok';
    }
  }

  /** First image URL when it is a resolvable http(s) URL, else null (placeholder). */
  thumb(product: Product): string | null {
    const key = product.images?.[0]?.objectKey;
    return key && /^https?:\/\//i.test(key) ? key : null;
  }

  // --- Add / Edit product form -------------------------------------------

  openCreate(): void {
    this.formError.set(null);
    this.editing.set(null);
    this.form.reset({
      sku: '',
      name: '',
      description: '',
      mrp: '',
      salePrice: '',
      hsnCode: '',
      gstRate: '',
      visibility: ProductVisibility.PUBLISHED,
      categoryId: '',
      stockQuantity: '0',
      trackInventory: false,
      featured: false,
    });
    this.creating.set(true);
  }

  openEdit(product: Product): void {
    this.formError.set(null);
    this.creating.set(false);
    this.form.reset({
      sku: product.sku,
      name: product.name,
      description: product.description ?? '',
      mrp: product.mrp,
      salePrice: product.salePrice,
      hsnCode: product.hsnCode ?? '',
      gstRate: product.gstRate ?? '',
      visibility: product.visibility,
      categoryId: product.category ? String(product.category.id) : '',
      stockQuantity: String(product.stockQuantity ?? 0),
      trackInventory: product.trackInventory ?? false,
      featured: product.featured ?? false,
    });
    this.editing.set(product);
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
    const request: ProductRequest = {
      sku: raw.sku.trim(),
      name: raw.name.trim(),
      description: raw.description.trim() || undefined,
      mrp: raw.mrp.trim(),
      salePrice: raw.salePrice.trim(),
      hsnCode: raw.hsnCode.trim() || undefined,
      gstRate: raw.gstRate.trim() || null,
      visibility: raw.visibility,
      categoryId: raw.categoryId ? Number(raw.categoryId) : null,
      stockQuantity: raw.stockQuantity ? Number(raw.stockQuantity) : 0,
      trackInventory: raw.trackInventory,
      featured: raw.featured,
    };

    this.saving.set(true);
    this.formError.set(null);
    const editing = this.editing();
    const op$ = editing
      ? this.service.update(editing.id, request)
      : this.service.create(request);

    op$.subscribe({
      next: () => {
        this.saving.set(false);
        this.showToast('ok', editing ? 'Product updated.' : 'Product created.');
        this.closeForm();
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  // --- Quick publish/hide toggle -----------------------------------------

  toggleVisibility(product: Product): void {
    if (this.togglingId() !== null) {
      return;
    }
    const next =
      product.visibility === ProductVisibility.PUBLISHED
        ? ProductVisibility.HIDDEN
        : ProductVisibility.PUBLISHED;
    const request: ProductRequest = {
      sku: product.sku,
      name: product.name,
      description: product.description ?? undefined,
      mrp: product.mrp,
      salePrice: product.salePrice,
      hsnCode: product.hsnCode ?? undefined,
      gstRate: product.gstRate ?? null,
      visibility: next,
      categoryId: product.category?.id ?? null,
      stockQuantity: product.stockQuantity ?? 0,
      trackInventory: product.trackInventory ?? false,
      featured: product.featured ?? false,
    };
    this.togglingId.set(product.id);
    this.service.update(product.id, request).subscribe({
      next: (updated) => {
        this.products.update((items) =>
          items.map((p) => (p.id === updated.id ? updated : p)),
        );
        this.togglingId.set(null);
        this.showToast(
          'ok',
          next === ProductVisibility.PUBLISHED
            ? `${product.name} is now published.`
            : `${product.name} is now hidden.`,
        );
      },
      error: () => {
        this.togglingId.set(null);
        this.showToast('error', `Could not update ${product.name}.`);
      },
    });
  }

  // --- Categories management ---------------------------------------------

  openCategories(): void {
    this.categoryFormError.set(null);
    this.resetCategoryForm();
    this.categoriesOpen.set(true);
  }

  closeCategories(): void {
    this.categoriesOpen.set(false);
    this.editingCategory.set(null);
    this.categoryFormError.set(null);
  }

  editCategory(category: Category): void {
    this.categoryFormError.set(null);
    this.categoryForm.reset({
      name: category.name,
      slug: category.slug,
      description: category.description ?? '',
      sortOrder: String(category.sortOrder ?? 0),
      active: category.active,
    });
    this.editingCategory.set(category);
  }

  newCategory(): void {
    this.categoryFormError.set(null);
    this.editingCategory.set(null);
    this.resetCategoryForm();
  }

  private resetCategoryForm(): void {
    this.categoryForm.reset({
      name: '',
      slug: '',
      description: '',
      sortOrder: '0',
      active: true,
    });
  }

  saveCategory(): void {
    if (this.categorySaving()) {
      return;
    }
    if (this.categoryForm.invalid) {
      this.categoryForm.markAllAsTouched();
      return;
    }
    const raw = this.categoryForm.getRawValue();
    const request: CategoryRequest = {
      name: raw.name.trim(),
      slug: raw.slug.trim() || undefined,
      description: raw.description.trim() || undefined,
      sortOrder: raw.sortOrder ? Number(raw.sortOrder) : 0,
      active: raw.active,
    };

    this.categorySaving.set(true);
    this.categoryFormError.set(null);
    const editing = this.editingCategory();
    const op$ = editing
      ? this.categoriesService.update(editing.id, request)
      : this.categoriesService.create(request);

    op$.subscribe({
      next: () => {
        this.categorySaving.set(false);
        this.showToast('ok', editing ? 'Category updated.' : 'Category created.');
        this.resetCategoryForm();
        this.editingCategory.set(null);
        this.loadCategories();
      },
      error: (err: HttpErrorResponse) => {
        this.categorySaving.set(false);
        this.categoryFormError.set(this.describeError(err));
      },
    });
  }

  async deactivateCategory(category: Category): Promise<void> {
    const confirmed = await this.confirmService.confirm({
      title: 'Deactivate category',
      message: `Deactivate "${category.name}"? Products in it stay, but the category is hidden from the storefront.`,
      confirmLabel: 'Deactivate',
      danger: true,
      icon: 'ti-eye-off',
    });
    if (!confirmed) {
      return;
    }
    this.categoriesService.deactivate(category.id).subscribe({
      next: () => {
        this.showToast('ok', `${category.name} deactivated.`);
        this.loadCategories();
      },
      error: () => this.showToast('error', `Could not deactivate ${category.name}.`),
    });
  }

  // --- CSV import (Set B — Feature 5) ------------------------------------

  openImport(): void {
    this.importFile.set(null);
    this.importError.set(null);
    this.importPreview.set(null);
    this.importResult.set(null);
    this.importBusy.set(false);
    this.importOpen.set(true);
  }

  closeImport(): void {
    this.importOpen.set(false);
  }

  /** Handles the file picker change; resets any prior preview/result. */
  onImportFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.importFile.set(file);
    this.importPreview.set(null);
    this.importResult.set(null);
    this.importError.set(null);
  }

  /** Step 1 — dry-run the import to preview counts + per-row outcomes. */
  previewImport(): void {
    const file = this.importFile();
    if (!file || this.importBusy()) {
      return;
    }
    this.importBusy.set(true);
    this.importError.set(null);
    this.importResult.set(null);
    this.service.importCsv(file, true).subscribe({
      next: (res) => {
        this.importPreview.set(res);
        this.importBusy.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.importBusy.set(false);
        this.importError.set(this.describeError(err));
      },
    });
  }

  /** Step 2 — commit the import (dryRun=false), then refresh the grid. */
  confirmImport(): void {
    const file = this.importFile();
    if (!file || this.importBusy()) {
      return;
    }
    this.importBusy.set(true);
    this.importError.set(null);
    this.service.importCsv(file, false).subscribe({
      next: (res) => {
        this.importResult.set(res);
        this.importBusy.set(false);
        this.showToast(
          'ok',
          `Import complete: ${res.created} created, ${res.updated} updated, ${res.skipped} skipped.`,
        );
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.importBusy.set(false);
        this.importError.set(this.describeError(err));
      },
    });
  }

  /** Generates a small sample CSV client-side and triggers a download. */
  downloadSampleCsv(): void {
    const header = this.importColumns;
    const sample = [
      header,
      'SHIFA-001,Herbal Tea 100g,299,249,09021010,5,50,true,Teas,PUBLISHED,Soothing herbal blend',
      'SHIFA-002,Neem Capsules 60ct,499,449,30049011,12,30,true,Supplements,PUBLISHED,Daily wellness',
    ].join('\n');
    const blob = new Blob([sample], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'product-import-sample.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  /** Tabler badge tone for a per-row import action. */
  importRowTone(action: string): string {
    switch (action) {
      case 'CREATE':
        return 'done';
      case 'UPDATE':
        return 'progress';
      case 'ERROR':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  // --- Helpers ------------------------------------------------------------

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.code === 'DUPLICATE_SKU') {
      return body.message || 'A product with this SKU already exists.';
    }
    if (body?.code === 'DUPLICATE_CATEGORY_NAME' || body?.code === 'DUPLICATE_CATEGORY_SLUG') {
      return body.message || 'A category with that name or slug already exists.';
    }
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not save. Please try again.';
  }

  private showToast(kind: 'ok' | 'error', text: string): void {
    if (kind === 'error') {
      this.toasts.error(text);
    } else {
      this.toasts.success(text);
    }
  }
}
