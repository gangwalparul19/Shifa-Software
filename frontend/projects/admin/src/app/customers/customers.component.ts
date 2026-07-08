import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormControl } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { Money, SortState } from 'core';
import { CustomersService } from './customers.service';
import { CustomerDetail, CustomerSummary } from './customers.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/** Sort fields the backend accepts for the admin customers listing. */
const SORT_FIELDS = new Set(['totalSpent', 'orderCount', 'lastOrderAt', 'firstOrderAt', 'mobile']);
const TABLE_KEY = 'customers';

/**
 * Admin Customers / CRM view (Set B — Feature 1).
 *
 * <p>Backed by the paginated {@code GET /api/admin/customers} endpoint with
 * server-side search, column sorting and paging. A row opens a detail drawer
 * showing the customer summary plus their order history; each order deep-links
 * to the Orders page filtered by that code ({@code /orders?q=<code>}).
 */
@Component({
  selector: 'admin-customers',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
    StatusBadgeComponent,
  ],
  templateUrl: './customers.component.html',
  styleUrl: './customers.component.css',
})
export class CustomersComponent implements OnInit, OnDestroy {
  private readonly service = inject(CustomersService);

  protected readonly customers = signal<CustomerSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 10));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'totalSpent', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });

  // --- Detail drawer ------------------------------------------------------
  protected readonly selectedDetail = signal<CustomerDetail | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.load();
    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.resetAndLoad());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service
      .page({
        q: this.search.value,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.customers.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load customers. Please try again.');
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

  money(value: Money | undefined | null): string {
    if (value === undefined || value === null) {
      return '₹0.00';
    }
    return `₹${value}`;
  }

  /**
   * Two-letter initials for the customer avatar chip (we have no customer
   * photos, so the mobile list uses an initials chip like the order drawer).
   */
  customerInitials(name: string | null | undefined): string {
    const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '?';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  // --- Detail drawer ------------------------------------------------------

  openDetail(customer: CustomerSummary): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedDetail.set(null);
    this.service.detail(customer.mobile).subscribe({
      next: (detail) => {
        this.selectedDetail.set(detail);
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailError.set('Could not load this customer.');
        this.detailLoading.set(false);
      },
    });
  }

  closeDetail(): void {
    this.selectedDetail.set(null);
    this.detailError.set(null);
  }
}
