import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { AuthService, Money, Role, SortState } from 'core';
import { ReturnsService } from './returns.service';
import { ReturnResponse, ReturnStatus } from './returns.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { ToastService } from '../shared/toast.service';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the admin returns listing. */
const SORT_FIELDS = new Set(['createdAt', 'updatedAt', 'status', 'refundAmount']);
const TABLE_KEY = 'returns';

/** Which action modal is currently open. */
type ReturnModal = 'approve' | 'reject' | 'refund' | 'create' | null;

/**
 * Admin Returns / Refunds view (Set B — Feature 2).
 *
 * <p>Filterable, paginated list of return requests with status-driven, role
 * gated row actions: Approve (restock + optional refund) and Reject are
 * ADMIN-only; Mark refunded is ADMIN + ACCOUNTANT. A return can also be created
 * here via an order-id picker (the primary create path lives on the order
 * detail view).
 */
@Component({
  selector: 'admin-returns',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './returns.component.html',
  styleUrl: './returns.component.css',
})
export class ReturnsComponent implements OnInit, OnDestroy {
  private readonly service = inject(ReturnsService);
  private readonly auth = inject(AuthService);
  private readonly toasts = inject(ToastService);

  protected readonly statusOptions: ReturnStatus[] = ['REQUESTED', 'APPROVED', 'REFUNDED', 'REJECTED'];

  /** Approve/Reject/Create are ADMIN-only; refund is ADMIN + ACCOUNTANT. */
  protected readonly canManage = computed(() => this.auth.hasAnyRole(Role.ADMIN));
  protected readonly canRefund = computed(() => this.auth.hasAnyRole(Role.ADMIN, Role.ACCOUNTANT));

  protected readonly returns = signal<ReturnResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 20));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'createdAt', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly filters = new FormGroup({
    status: new FormControl<string>('', { nonNullable: true }),
    from: new FormControl<string>('', { nonNullable: true }),
    to: new FormControl<string>('', { nonNullable: true }),
  });

  // --- Action modals ------------------------------------------------------
  protected readonly modal = signal<ReturnModal>(null);
  protected readonly activeReturn = signal<ReturnResponse | null>(null);
  protected readonly submitting = signal(false);
  protected readonly modalError = signal<string | null>(null);

  protected readonly approveForm = new FormGroup({
    restock: new FormControl<boolean>(true, { nonNullable: true }),
    refundAmount: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)],
    }),
  });
  protected readonly rejectNotes = new FormControl<string>('', { nonNullable: true });
  protected readonly refundAmount = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^\d{1,10}(\.\d{1,2})?$/)],
  });
  protected readonly createForm = new FormGroup({
    orderId: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^\d{1,18}$/)],
    }),
    reason: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)],
    }),
    notes: new FormControl<string>('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
  });

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
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

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        q: this.search.value,
        status: f.status || null,
        from: f.from || null,
        to: f.to || null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.returns.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load returns. Please try again.');
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
    this.filters.reset({ status: '', from: '', to: '' });
    this.search.setValue('');
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(this.search.value || f.status || f.from || f.to);
  }

  // --- Presentation helpers ----------------------------------------------

  /** Maps a return status to a shared badge tone (amber/blue/green/red). */
  statusTone(status: ReturnStatus | string): string {
    switch (status) {
      case 'REQUESTED':
        return 'pending';
      case 'APPROVED':
        return 'progress';
      case 'REFUNDED':
        return 'done';
      case 'REJECTED':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  money(value: Money | undefined | null): string {
    if (value === undefined || value === null) {
      return '—';
    }
    return `₹${value}`;
  }

  canApproveOrReject(r: ReturnResponse): boolean {
    return this.canManage() && r.status === 'REQUESTED';
  }

  canMarkRefunded(r: ReturnResponse): boolean {
    return this.canRefund() && r.status === 'APPROVED';
  }

  // --- Modals -------------------------------------------------------------

  openApprove(r: ReturnResponse): void {
    this.activeReturn.set(r);
    this.modalError.set(null);
    this.approveForm.reset({ restock: true, refundAmount: r.refundAmount ? String(r.refundAmount) : '' });
    this.modal.set('approve');
  }

  openReject(r: ReturnResponse): void {
    this.activeReturn.set(r);
    this.modalError.set(null);
    this.rejectNotes.setValue('');
    this.modal.set('reject');
  }

  openRefund(r: ReturnResponse): void {
    this.activeReturn.set(r);
    this.modalError.set(null);
    this.refundAmount.setValue(r.refundAmount ? String(r.refundAmount) : '');
    this.modal.set('refund');
  }

  openCreate(): void {
    this.activeReturn.set(null);
    this.modalError.set(null);
    this.createForm.reset({ orderId: '', reason: '', notes: '' });
    this.modal.set('create');
  }

  closeModal(): void {
    this.modal.set(null);
    this.activeReturn.set(null);
    this.modalError.set(null);
    this.submitting.set(false);
  }

  submitApprove(): void {
    const r = this.activeReturn();
    if (!r || this.submitting() || this.approveForm.invalid) {
      this.approveForm.markAllAsTouched();
      return;
    }
    const raw = this.approveForm.getRawValue();
    this.submitting.set(true);
    this.modalError.set(null);
    this.service
      .approve(r.id, {
        restock: raw.restock,
        refundAmount: raw.refundAmount ? Number(raw.refundAmount) : undefined,
      })
      .subscribe({
        next: () => this.afterMutation('Return approved.'),
        error: () => this.failMutation('Could not approve the return.'),
      });
  }

  submitReject(): void {
    const r = this.activeReturn();
    if (!r || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.modalError.set(null);
    this.service.reject(r.id, { notes: this.rejectNotes.value.trim() || undefined }).subscribe({
      next: () => this.afterMutation('Return rejected.'),
      error: () => this.failMutation('Could not reject the return.'),
    });
  }

  submitRefund(): void {
    const r = this.activeReturn();
    if (!r || this.submitting() || this.refundAmount.invalid) {
      this.refundAmount.markAsTouched();
      return;
    }
    this.submitting.set(true);
    this.modalError.set(null);
    this.service.refund(r.id, { refundAmount: Number(this.refundAmount.value) }).subscribe({
      next: () => this.afterMutation('Return marked refunded.'),
      error: () => this.failMutation('Could not mark the return refunded.'),
    });
  }

  submitCreate(): void {
    if (this.submitting() || this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const raw = this.createForm.getRawValue();
    this.submitting.set(true);
    this.modalError.set(null);
    this.service
      .create({
        orderId: Number(raw.orderId),
        reason: raw.reason.trim(),
        notes: raw.notes.trim() || undefined,
      })
      .subscribe({
        next: () => this.afterMutation('Return created.'),
        error: () => this.failMutation('Could not create the return. Check the order id.'),
      });
  }

  private afterMutation(message: string): void {
    this.submitting.set(false);
    this.toasts.success(message);
    this.closeModal();
    this.load();
  }

  private failMutation(message: string): void {
    this.submitting.set(false);
    this.modalError.set(message);
  }
}
