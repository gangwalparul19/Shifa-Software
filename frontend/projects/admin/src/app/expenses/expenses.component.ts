import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { ApiError, SortState } from 'core';
import { ExpenseRequest, ExpenseResponse } from './expenses.model';
import { ExpensesService } from './expenses.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the admin expenses listing. */
const SORT_FIELDS = new Set(['incurredOn', 'createdAt', 'amount', 'category']);
const TABLE_KEY = 'expenses';

/**
 * Admin Expenses (Phase C3 — ADMIN + ACCOUNTANT).
 *
 * <p>A filterable, sortable, paginated table of recorded business expenses
 * (category text filter plus a from/to incurred-on date range). New expenses
 * are recorded through a reactive-form modal; each row can be deleted after a
 * confirmation.
 */
@Component({
  selector: 'admin-expenses',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './expenses.component.html',
  styleUrl: './expenses.component.css',
})
export class ExpensesComponent implements OnInit, OnDestroy {
  private readonly service = inject(ExpensesService);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly expenses = signal<ExpenseResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly deletingId = signal<number | null>(null);

  /** Sum of the amounts on the current page (summary tile). */
  protected readonly pageTotal = computed(() =>
    this.expenses().reduce((sum, e) => sum + (Number.isFinite(e.amount) ? e.amount : 0), 0),
  );

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 20));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'incurredOn', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly filters = new FormGroup({
    category: new FormControl<string>('', { nonNullable: true }),
    from: new FormControl<string>('', { nonNullable: true }),
    to: new FormControl<string>('', { nonNullable: true }),
  });

  // --- Add form -----------------------------------------------------------
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    category: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    description: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
    amount: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)],
    }),
    incurredOn: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.load();
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
        category: f.category || null,
        from: f.from || null,
        to: f.to || null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.expenses.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load expenses. Please try again.');
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

  clearFilters(): void {
    this.filters.reset({ category: '', from: '', to: '' });
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(f.category || f.from || f.to);
  }

  money(value: number | null | undefined): string {
    const n = typeof value === 'number' && Number.isFinite(value) ? value : 0;
    return `₹${n.toFixed(2)}`;
  }

  // --- Add form -----------------------------------------------------------

  openCreate(): void {
    this.formError.set(null);
    this.form.reset({
      category: '',
      description: '',
      amount: '',
      incurredOn: this.today(),
    });
    this.creating.set(true);
  }

  closeCreate(): void {
    this.creating.set(false);
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
    const request: ExpenseRequest = {
      category: raw.category.trim(),
      description: raw.description.trim() || null,
      amount: Number(raw.amount),
      incurredOn: raw.incurredOn,
    };

    this.saving.set(true);
    this.formError.set(null);
    this.service.create(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.success('Expense recorded.');
        this.closeCreate();
        this.resetAndLoad();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  // --- Delete -------------------------------------------------------------

  async remove(expense: ExpenseResponse): Promise<void> {
    if (this.deletingId() !== null) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Delete expense',
      message: `Delete the ${this.money(expense.amount)} "${expense.category}" expense? This cannot be undone.`,
      confirmLabel: 'Delete',
      danger: true,
      icon: 'ti-trash',
    });
    if (!confirmed) {
      return;
    }
    this.deletingId.set(expense.id);
    this.service.delete(expense.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.toasts.success('Expense deleted.');
        this.load();
      },
      error: () => {
        this.deletingId.set(null);
        this.toasts.error('Could not delete the expense.');
      },
    });
  }

  private today(): string {
    const d = new Date();
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Could not save. Please try again.';
  }
}
