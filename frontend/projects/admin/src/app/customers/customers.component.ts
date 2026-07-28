import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { Money, SortState } from 'core';
import { CustomersService } from './customers.service';
import { CustomerProfile, CustomerSummary, riskLabel, riskPillClass } from './customers.model';
import { ToastService } from '../shared/toast.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { WHATSAPP_TEMPLATES, openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';

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
  private readonly toasts = inject(ToastService);

  // Expose risk badge helpers to the template.
  protected readonly riskPillClass = riskPillClass;
  protected readonly riskLabel = riskLabel;

  /** One-tap WhatsApp templates for the Customer 360 drawer. */
  protected readonly whatsappTemplates = WHATSAPP_TEMPLATES;

  /**
   * The most recent order id to reorder from (first history row that carries an
   * id; the list is newest-first). Null when none is available.
   */
  lastReorderableId(profile: CustomerProfile): number | null {
    return profile.orders.find((o) => o.orderId != null)?.orderId ?? null;
  }

  /** Opens WhatsApp for the open customer with a pre-filled template message. */
  sendWhatsApp(profile: CustomerProfile, key: string): void {
    const ok = openWhatsApp(
      profile.summary.mobile,
      whatsAppMessage(key, {
        customerName: profile.summary.name,
        total: profile.summary.totalSpent,
        remaining: profile.metrics.outstanding,
      }),
    );
    if (!ok) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }

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

  // --- Detail drawer (Customer 360) ---------------------------------------
  protected readonly selectedProfile = signal<CustomerProfile | null>(null);
  /**
   * Active tab in the Customer 360 drawer so its (long) content is split into
   * Overview / CRM / Orders tabs instead of one long scroll.
   */
  protected readonly custTab = signal<'overview' | 'crm' | 'orders'>('overview');
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);

  // --- Tag + note editing in the drawer -----------------------------------
  protected readonly newTag = new FormControl<string>('', { nonNullable: true });
  protected readonly savingTag = signal(false);
  protected readonly newNote = new FormControl<string>('', { nonNullable: true });
  protected readonly savingNote = signal(false);

  // --- Order-history paging inside the drawer (the history can grow long) --
  protected readonly historyPage = signal(0);
  protected readonly historySize = signal(8);
  protected readonly historyTotalPages = computed(() =>
    Math.max(1, Math.ceil((this.selectedProfile()?.orders.length ?? 0) / this.historySize())),
  );
  protected readonly historyPageItems = computed(() => {
    const orders = this.selectedProfile()?.orders ?? [];
    const start = this.historyPage() * this.historySize();
    return orders.slice(start, start + this.historySize());
  });

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
    this.custTab.set('overview');
    this.selectedProfile.set(null);
    this.historyPage.set(0);
    this.newTag.reset('');
    this.newNote.reset('');
    this.service.profile(customer.mobile).subscribe({
      next: (profile) => {
        this.selectedProfile.set(profile);
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailError.set('Could not load this customer.');
        this.detailLoading.set(false);
      },
    });
  }

  /** The mobile of the customer currently open in the drawer, or null. */
  private currentMobile(): string | null {
    return this.selectedProfile()?.summary.mobile ?? null;
  }

  addTag(): void {
    const mobile = this.currentMobile();
    const tag = this.newTag.value.trim();
    if (!mobile || !tag || this.savingTag()) {
      return;
    }
    this.savingTag.set(true);
    this.service.addTag(mobile, tag).subscribe({
      next: (tags) => {
        this.patchProfile({ tags });
        this.newTag.reset('');
        this.savingTag.set(false);
      },
      error: () => {
        this.toasts.error('Could not add the tag.');
        this.savingTag.set(false);
      },
    });
  }

  removeTag(tag: string): void {
    const mobile = this.currentMobile();
    if (!mobile) {
      return;
    }
    this.service.removeTag(mobile, tag).subscribe({
      next: (tags) => this.patchProfile({ tags }),
      error: () => this.toasts.error('Could not remove the tag.'),
    });
  }

  addNote(): void {
    const mobile = this.currentMobile();
    const note = this.newNote.value.trim();
    if (!mobile || !note || this.savingNote()) {
      return;
    }
    this.savingNote.set(true);
    this.service.addNote(mobile, note).subscribe({
      next: (notes) => {
        this.patchProfile({ notes });
        this.newNote.reset('');
        this.savingNote.set(false);
        this.toasts.success('Note added');
      },
      error: () => {
        this.toasts.error('Could not add the note.');
        this.savingNote.set(false);
      },
    });
  }

  /** Immutably patches fields of the open profile signal. */
  private patchProfile(patch: Partial<CustomerProfile>): void {
    const current = this.selectedProfile();
    if (current) {
      this.selectedProfile.set({ ...current, ...patch });
    }
  }

  /** Percentage (0–100) for a 0..1 rate, rounded. */
  pct(rate: number): number {
    return Math.round((rate ?? 0) * 100);
  }

  goToHistoryPage(page: number): void {
    this.historyPage.set(page);
  }

  setHistorySize(size: number): void {
    this.historySize.set(size);
    this.historyPage.set(0);
  }

  closeDetail(): void {
    this.selectedProfile.set(null);
    this.detailError.set(null);
  }
}
