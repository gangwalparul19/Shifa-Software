import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { ExceptionsService } from './exceptions.service';
import {
  AdminExceptionItem,
  AdminExceptionResponse,
  ExceptionCategory,
  EXCEPTION_CATEGORY_LABELS,
} from './exceptions.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import {
  SourceFilterMode,
  matchesSourceMode,
  readSourceFilter,
  writeSourceFilter,
} from '../shared/source-filter.util';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { OrderStatus } from 'core';
import { humanizeStatus } from '../shared/status-badge.component';

@Component({
  selector: 'admin-exceptions',
  imports: [RouterLink, IstDatePipe, PageHeaderComponent, PaginationComponent, StatePanelComponent],
  templateUrl: './exceptions.component.html',
  styleUrl: './exceptions.component.css',
})
export class ExceptionsComponent implements OnInit {
  private readonly service = inject(ExceptionsService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);

  protected readonly response = signal<AdminExceptionResponse | null>(null);
  protected readonly selectedCategory = signal<ExceptionCategory | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Source filter (Portal / Shopify / All, default Portal) -------------
  protected readonly sourceFilter = signal<SourceFilterMode>(readSourceFilter('shifa:exceptions-source'));

  setSourceFilter(mode: SourceFilterMode): void {
    this.sourceFilter.set(mode);
    writeSourceFilter('shifa:exceptions-source', mode);
    this.page.set(0);
  }

  protected readonly categories = Object.keys(EXCEPTION_CATEGORY_LABELS) as ExceptionCategory[];
  protected readonly categoryLabels = EXCEPTION_CATEGORY_LABELS;

  /** Rows matching the source filter, before the optional category lens. */
  protected readonly sourceItems = computed<AdminExceptionItem[]>(() => {
    const mode = this.sourceFilter();
    return (this.response()?.items ?? []).filter((item) => matchesSourceMode(item.source, mode));
  });

  /** Rows currently shown after source + category filtering. */
  protected readonly filteredItems = computed<AdminExceptionItem[]>(() => {
    const category = this.selectedCategory();
    return category
      ? this.sourceItems().filter((item) => item.category === category)
      : this.sourceItems();
  });

  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('exceptions', 10));
  protected readonly totalElements = computed(() => this.filteredItems().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed<AdminExceptionItem[]>(() => {
    const start = this.page() * this.size();
    return this.filteredItems().slice(start, start + this.size());
  });

  /** Category counts remain visible for the current source, even when a category is selected. */
  count(category: ExceptionCategory): number {
    return this.sourceItems().filter((item) => item.category === category).length;
  }

  setCategory(category: ExceptionCategory | null): void {
    this.selectedCategory.set(category);
    this.page.set(0);
  }

  resetFilters(): void {
    this.selectedCategory.set(null);
    this.setSourceFilter('PORTAL');
  }

  goToPage(page: number): void {
    this.page.set(page);
  }

  setSize(size: number): void {
    this.size.set(size);
    writePageSize('exceptions', size);
    this.page.set(0);
  }

  /** Human-readable source label for order-backed exception rows. */
  sourceLabel(source: string | null | undefined): string {
    return source === 'SHOPIFY' ? 'Shopify' : source ? 'Portal' : 'Business-wide';
  }

  sourceBadgeClass(source: string | null | undefined): string {
    return source === 'SHOPIFY' ? 'bg-green-lt text-green' : 'bg-secondary-lt text-secondary';
  }

  categoryLabel(category: string): string {
    return this.categoryLabels[category as ExceptionCategory] ?? category;
  }

  ngOnInit(): void {
    const category = this.route.snapshot.queryParamMap.get('category') as ExceptionCategory | null;
    if (category && this.categories.includes(category)) {
      this.selectedCategory.set(category);
    }
    const source = this.route.snapshot.queryParamMap.get('source');
    if (source === 'PORTAL' || source === 'SHOPIFY' || source === 'ALL') {
      this.sourceFilter.set(source);
      writeSourceFilter('shifa:exceptions-source', source);
    } else if (category === 'QUIKSHIP') {
      // QuikShip failures can come from both portal and Shopify orders. A
      // dashboard drill-down must not inherit the page's Portal-only default.
      this.sourceFilter.set('ALL');
    }
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (value) => {
        this.response.set(value);
        this.page.set(0);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loadError.set(error.status === 403
          ? 'Only administrators can view the exception center.'
          : 'Could not load exceptions. Please try again.');
        this.loading.set(false);
      },
    });
  }

  categoryIcon(category: string): string {
    switch (category) {
      case 'APPROVAL': return 'ti-checklist';
      case 'PAYMENT': return 'ti-shield-check';
      case 'DELIVERY': return 'ti-truck-off';
      case 'CLAIM': return 'ti-file-alert';
      case 'QUIKSHIP': return 'ti-truck-delivery';
      case 'INSIGHT': return 'ti-bulb';
      default: return 'ti-alert-triangle';
    }
  }

  severityClass(severity: string): string {
    return severity === 'DANGER' || severity === 'HIGH'
      ? 'bg-red-lt text-red'
      : severity === 'WARNING' || severity === 'MEDIUM'
        ? 'bg-yellow-lt text-yellow'
        : 'bg-blue-lt text-blue';
  }

  statusLabel(status: OrderStatus | null | undefined): string {
    return status ? humanizeStatus(status) : '';
  }

  money(value: string | number | null | undefined): string {
    const amount = Number(value ?? 0);
    return Number.isFinite(amount) ? `₹${amount.toLocaleString('en-IN', { maximumFractionDigits: 2 })}` : '₹0';
  }

  actionLabel(item: AdminExceptionItem): string {
    switch (item.category) {
      case 'APPROVAL': return 'Review approval';
      case 'PAYMENT': return 'Review payment';
      case 'DELIVERY': return 'Open order';
      case 'CLAIM': return 'Open reconciliation';
      case 'QUIKSHIP': return 'Open order';
      case 'INSIGHT': return 'Open insight';
      default: return 'Open';
    }
  }

  actionQuery(item: AdminExceptionItem): Record<string, string> | null {
    return item.orderCode ? { q: item.orderCode } : null;
  }

  call(item: AdminExceptionItem, event: Event): void {
    event.stopPropagation();
    if (item.customerMobile) {
      window.location.href = `tel:${item.customerMobile}`;
    }
  }

  message(item: AdminExceptionItem, event: Event): void {
    event.stopPropagation();
    if (!item.customerMobile) {
      this.toasts.error('This exception has no customer mobile number.');
      return;
    }
    if (!openWhatsApp(item.customerMobile, whatsAppMessage('followup', {
      customerName: item.customerName,
      orderCode: item.orderCode,
    }))) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }
}
