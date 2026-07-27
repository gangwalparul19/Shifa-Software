import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { RouterLink } from '@angular/router';
import { AuthService, Money, OrderStatus, PaymentStatus, Role, SortDir, SortState } from 'core';
import { OrdersService } from './orders.service';
import { OrderDetail, OrderDetailLine, OrderSummary } from './orders.model';
import { ReturnsService } from '../returns/returns.service';
import {
  PLACEHOLDER_PRODUCT_IMAGE,
  imageErrorFallback,
  resolveImageUrl,
} from '../shared/product-image.util';
import { AdminEventsService } from '../dashboard/admin-events.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatusBadgeComponent, humanizeStatus } from '../shared/status-badge.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import {
  SavedView,
  loadSavedViews,
  newViewId,
  persistSavedViews,
} from './saved-views.util';
import {
  ORDER_STATUS_GROUPS,
  OrderStatusGroupKey,
  groupForStatus,
} from './order-status-groups';

/** Sort fields the backend accepts for the admin orders listing. */
const SORT_FIELDS = new Set([
  'createdAt',
  'orderCode',
  'customerName',
  'totalAmount',
  'orderStatus',
  'paymentStatus',
]);

const TABLE_KEY = 'orders';

/**
 * A selected status filter: either the "ALL" sentinel or one of the coarse
 * business-facing lifecycle groups (see {@link OrderStatusGroupKey}). The
 * grouped status filter is applied SERVER-SIDE (via {@code ?statusGroup=}) so
 * it is correct across pagination, and it is shared by both the quick tab strip
 * and the advanced-filter dropdown.
 */
export type OrderStatusFilter = OrderStatusGroupKey | 'ALL';

/**
 * Colour buckets for the status pill in the detail/mobile views (green =
 * successful terminal, red = cancelled/failed/returned, amber = everything
 * still in flight). Distinct from the filter groups — this is display only.
 */
const PILL_COMPLETED: OrderStatus[] = [
  OrderStatus.DELIVERED,
  OrderStatus.COD_COLLECTED,
  OrderStatus.CLOSED,
];
const PILL_BAD: OrderStatus[] = [
  OrderStatus.REJECTED,
  OrderStatus.CANCELLED,
  OrderStatus.CUSTOMER_REJECTED,
  OrderStatus.DELIVERY_FAILED,
  OrderStatus.RTO,
  OrderStatus.COURIER_LOST,
];

/**
 * The quick status tabs shown above the list, in lifecycle order: "All" plus
 * every business-facing group. Selecting one drives the shared server-side
 * {@code statusGroup} filter.
 */
export const ORDER_STATUS_TABS: { key: OrderStatusFilter; label: string }[] = [
  { key: 'ALL', label: 'All' },
  ...ORDER_STATUS_GROUPS.map((g) => ({ key: g.key as OrderStatusFilter, label: g.label })),
];

/**
 * Admin all-orders view (Req 21, 22) — Wave 2 server-side edition.
 *
 * <p>Now backed by the paginated {@code GET /api/admin/orders} endpoint with
 * server-side filtering (search, status, payment status, date range), column
 * sorting, and paging. Adds bulk operations: select rows (or all on the page)
 * and approve / mark-packed / print-labels the selection, reporting partial
 * results. A row opens a mobile-first detail drawer with full line items,
 * totals, payment summary, and shipment info. (Online-payment and payment-
 * screenshot sections were dropped with the dashboard-only pivot.)
 */
@Component({
  selector: 'admin-orders',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    PageHeaderComponent,
    StatusBadgeComponent,
    StatePanelComponent,
    DensityToggleComponent,
    PaginationComponent,
    SortableHeaderComponent,
  ],
  templateUrl: './orders.component.html',
  styleUrl: './orders.component.css',
})
export class OrdersComponent implements OnInit, OnDestroy {
  private readonly service = inject(OrdersService);
  private readonly returnsService = inject(ReturnsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  protected readonly events = inject(AdminEventsService);

  /** Whether the current user may punch a new order (SALESPERSON + ADMIN, Req 7). */
  protected readonly canCreateOrder = computed(() =>
    this.auth.hasAnyRole(Role.SALESPERSON, Role.ADMIN),
  );

  /** Creating a return is ADMIN-only (Set B — Feature 2, mutations = ADMIN). */
  protected readonly canCreateReturn = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  /** A return may be raised only for a delivered or RTO'd order. */
  isReturnEligible(order: OrderDetail | null): boolean {
    return (
      !!order &&
      (order.orderStatus === OrderStatus.DELIVERED || order.orderStatus === OrderStatus.RTO)
    );
  }

  protected readonly OrderStatus = OrderStatus;
  protected readonly PaymentStatus = PaymentStatus;
  protected readonly humanize = humanizeStatus;
  /** Grouped status options for the filter dropdown (clubs the raw statuses). */
  protected readonly statusGroups = ORDER_STATUS_GROUPS;
  protected readonly paymentOptions = Object.values(PaymentStatus);

  protected readonly orders = signal<OrderSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Status filter tabs (grouped, server-side) --------------------------
  protected readonly statusTabs = ORDER_STATUS_TABS;
  /**
   * The currently selected status filter, mirroring the {@code statusGroup}
   * form control ('' → 'ALL'). Drives the active tab highlight. Filtering itself
   * is server-side, so the loaded page already only contains matching orders.
   */
  protected readonly activeStatusGroup = signal<OrderStatusFilter>('ALL');
  /**
   * The orders to render. Filtering is now done server-side (grouped
   * {@code statusGroup} query param), so this simply surfaces the loaded page —
   * kept as a named accessor so the template markup is unchanged.
   */
  protected readonly visibleOrders = computed<OrderSummary[]>(() => this.orders());

  // --- Paging + sort ------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 10));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly sort = signal<SortState>({ field: 'createdAt', dir: 'desc' });

  // --- Filters ------------------------------------------------------------
  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly filters = new FormGroup({
    statusGroup: new FormControl<string>('', { nonNullable: true }),
    paymentStatus: new FormControl<string>('', { nonNullable: true }),
    from: new FormControl<string>('', { nonNullable: true }),
    to: new FormControl<string>('', { nonNullable: true }),
  });

  /** Whether the collapsible filter panel is expanded (collapsed by default). */
  protected readonly filtersOpen = signal(false);
  /** Number of active advanced filters (Status/Payment/From/To), for the toggle badge. */
  protected readonly activeFilterCount = signal(0);

  /** Show/hide the advanced-filter panel. */
  toggleFilters(): void {
    this.filtersOpen.update((open) => !open);
  }

  // --- Bulk selection -----------------------------------------------------
  protected readonly selected = signal<Set<number>>(new Set());
  protected readonly bulkBusy = signal(false);
  protected readonly lastSkips = signal<{ id: number; reason: string }[]>([]);
  protected readonly showSkips = signal(false);

  protected readonly selectionCount = computed(() => this.selected().size);
  protected readonly allOnPageSelected = computed(() => {
    const rows = this.orders();
    if (rows.length === 0) {
      return false;
    }
    const sel = this.selected();
    return rows.every((o) => sel.has(o.id));
  });

  // --- Detail drawer ------------------------------------------------------
  protected readonly selectedDetail = signal<OrderDetail | null>(null);
  /**
   * Active tab in the order detail drawer so its (long) content is split into
   * Details / Items / Payment tabs instead of one long scroll. The action
   * buttons stay pinned below the tabs, visible from any tab.
   */
  protected readonly detailTab = signal<'details' | 'items' | 'payment'>('details');
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly invoiceLoading = signal(false);
  protected readonly invoiceError = signal<string | null>(null);
  protected readonly labelLoading = signal(false);
  /** Busy flag for single-order lifecycle actions in the detail drawer. */
  protected readonly detailBusy = signal(false);

  // --- Payment screenshot (admin/accountant review) -----------------------
  /** Object URL of the fetched payment screenshot for the open order, if any. */
  protected readonly screenshotUrl = signal<string | null>(null);
  protected readonly screenshotLoading = signal(false);
  /** True when a screenshot was expected but could not be fetched. */
  protected readonly screenshotMissing = signal(false);

  /**
   * Whether the acting role may view an order's payment screenshot. The backend
   * gates {@code GET /api/orders/{id}/payment-screenshot} to ACCOUNTANT/ADMIN, so
   * we only fetch/show it for those roles (a salesperson would get a 403).
   */
  protected readonly canViewScreenshot = computed(() =>
    this.auth.hasAnyRole(Role.ADMIN, Role.ACCOUNTANT),
  );

  // --- Create return (Set B — Feature 2) ---------------------------------
  /** Whether the create-return modal is open for the current detail order. */
  protected readonly createReturnOpen = signal(false);
  protected readonly createReturnBusy = signal(false);
  protected readonly createReturnError = signal<string | null>(null);
  protected readonly returnForm = new FormGroup({
    reason: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)],
    }),
    notes: new FormControl<string>('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
  });

  // --- Real-time activity pill (A3) ---------------------------------------
  /** True when a relevant order event arrived since the last (re)load. */
  protected readonly newActivity = signal(false);
  /** Guards the very first effect run so we don't flag pre-existing events. */
  private activityInitialised = false;
  /** Timestamp (ms) of the newest relevant notification we've accounted for. */
  private lastSeenActivityTs = 0;

  // --- Saved views (A4) ---------------------------------------------------
  protected readonly savedViews = signal<SavedView[]>([]);
  protected readonly savingView = signal(false);
  protected readonly newViewName = new FormControl<string>('', { nonNullable: true });

  private readonly destroy$ = new Subject<void>();

  constructor() {
    // Watch the shared SSE feed; surface a subtle "new activity" pill when an
    // order the list may contain changes, rather than yanking the page from
    // under the user (A3). Click-to-refresh keeps filters/page/selection.
    effect(() => {
      const notes = this.events.notifications();
      const relevant = notes.filter(
        (n) => n.type === 'ORDER_PACKED' || n.type === 'ORDER_STATUS_CHANGED',
      );
      const newestTs = relevant.length ? relevant[0].receivedAt.getTime() : 0;
      if (!this.activityInitialised) {
        this.activityInitialised = true;
        this.lastSeenActivityTs = newestTs;
        return;
      }
      if (newestTs > this.lastSeenActivityTs) {
        this.lastSeenActivityTs = newestTs;
        this.newActivity.set(true);
      }
    });
  }

  ngOnInit(): void {
    this.initFiltersFromQueryParams();
    this.savedViews.set(loadSavedViews());
    this.load();
    // Reuse the shell's singleton SSE stream (idempotent; no second source).
    this.events.connect();

    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe((term) => {
        // A free-text search is global. Don't let an active stage tab hide a
        // match that lives in another stage (a common trap: searching an order
        // code while a stage tab is selected shows "no orders in this stage").
        // Snap the stage filter back to "All" whenever the user is searching.
        if (term && term.trim() && this.filters.controls.statusGroup.value) {
          this.filters.controls.statusGroup.setValue('', { emitEvent: false });
          this.syncActiveStatusGroup();
          this.updateActiveFilterCount();
        }
        this.resetAndLoad();
      });

    this.filters.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.syncActiveStatusGroup();
      this.updateActiveFilterCount();
      this.resetAndLoad();
    });
    this.syncActiveStatusGroup();
    this.updateActiveFilterCount();
  }

  /** Mirrors the {@code statusGroup} control into the tab-highlight signal. */
  private syncActiveStatusGroup(): void {
    const value = this.filters.controls.statusGroup.value;
    this.activeStatusGroup.set((value || 'ALL') as OrderStatusFilter);
  }

  /** Recomputes how many of the advanced filters (Status/Payment/From/To) are set. */
  private updateActiveFilterCount(): void {
    const f = this.filters.getRawValue();
    this.activeFilterCount.set(
      [f.statusGroup, f.paymentStatus, f.from, f.to].filter((v) => !!v).length,
    );
  }

  /**
   * Seeds the search / filter controls from the URL query params so deep links
   * and dashboard drill-downs (?status=&paymentStatus=&from=&to=&q=) land the
   * list pre-filtered. Values are set without emitting so we issue a single
   * initial load (Set A — A2).
   */
  private initFiltersFromQueryParams(): void {
    const qp = this.route.snapshot.queryParamMap;
    const q = qp.get('q');
    if (q) {
      this.search.setValue(q, { emitEvent: false });
    }
    // Accept either the grouped ?statusGroup= or a legacy raw ?status= (mapped
    // to its group) so dashboard drill-downs and old deep links still land
    // pre-filtered. But a search deep link (?q=) is global — never pin it to a
    // single stage, or the searched order (which lives in one stage) would be
    // hidden under a different stage tab.
    const statusGroup = q ? '' : (qp.get('statusGroup') ?? groupForStatus(qp.get('status')));
    const paymentStatus = qp.get('paymentStatus') ?? '';
    const from = qp.get('from') ?? '';
    const to = qp.get('to') ?? '';
    if (statusGroup || paymentStatus || from || to) {
      this.filters.setValue({ statusGroup, paymentStatus, from, to }, { emitEvent: false });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        q: this.search.value,
        statusGroup: f.statusGroup || null,
        paymentStatus: f.paymentStatus || null,
        from: f.from || null,
        to: f.to || null,
        page: this.page(),
        size: this.size(),
        sort: sortParam(this.sort()),
      })
      .subscribe({
        next: (res) => {
          this.orders.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          // Drop any selected rows no longer present on the page view.
          this.pruneSelection(res.content);
          this.loading.set(false);
          // We're now in sync with the live feed: clear the activity pill.
          this.acknowledgeActivity();
        },
        error: () => {
          this.loadError.set('Could not load orders. Please try again.');
          this.loading.set(false);
        },
      });
  }

  /** Reset to the first page (used on any filter/sort change) and reload. */
  private resetAndLoad(): void {
    this.page.set(0);
    this.load();
  }

  /**
   * Selects a status group from the quick tab strip. Writes to the shared
   * {@code statusGroup} filter control ('' for "All"), which triggers a
   * server-side reload via the filters subscription.
   */
  setStatusGroup(group: OrderStatusFilter): void {
    this.filters.controls.statusGroup.setValue(group === 'ALL' ? '' : group);
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
    this.filters.reset({ statusGroup: '', paymentStatus: '', from: '', to: '' });
    this.search.setValue('');
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return !!(this.search.value || f.statusGroup || f.paymentStatus || f.from || f.to);
  }

  // --- Bulk selection -----------------------------------------------------

  isSelected(id: number): boolean {
    return this.selected().has(id);
  }

  toggleRow(id: number): void {
    this.selected.update((set) => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  toggleAllOnPage(): void {
    const rows = this.orders();
    this.selected.update((set) => {
      const next = new Set(set);
      const allSelected = rows.every((o) => next.has(o.id));
      if (allSelected) {
        rows.forEach((o) => next.delete(o.id));
      } else {
        rows.forEach((o) => next.add(o.id));
      }
      return next;
    });
  }

  clearSelection(): void {
    this.selected.set(new Set());
    this.lastSkips.set([]);
    this.showSkips.set(false);
  }

  private pruneSelection(rows: OrderSummary[]): void {
    const ids = new Set(rows.map((o) => o.id));
    this.selected.update((set) => {
      const next = new Set<number>();
      set.forEach((id) => {
        if (ids.has(id)) {
          next.add(id);
        }
      });
      return next;
    });
  }

  // --- Bulk actions -------------------------------------------------------

  async approveSelected(): Promise<void> {
    const ids = [...this.selected()];
    if (ids.length === 0 || this.bulkBusy()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Approve selected orders',
      message: `Approve ${ids.length} selected order${ids.length === 1 ? '' : 's'}? Ineligible orders are skipped.`,
      confirmLabel: 'Approve',
      icon: 'ti-check',
    });
    if (!confirmed) {
      return;
    }
    this.bulkBusy.set(true);
    this.service.bulkApprove(ids).subscribe({
      next: (res) => this.afterBulk(res, 'approved'),
      error: () => {
        this.bulkBusy.set(false);
        this.toasts.error('Bulk approve failed. Please try again.');
      },
    });
  }

  async markPackedSelected(): Promise<void> {
    const ids = [...this.selected()];
    if (ids.length === 0 || this.bulkBusy()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Mark selected as packed',
      message: `Mark ${ids.length} selected order${ids.length === 1 ? '' : 's'} as packed? Ineligible orders are skipped.`,
      confirmLabel: 'Mark packed',
      icon: 'ti-package',
    });
    if (!confirmed) {
      return;
    }
    this.bulkBusy.set(true);
    this.service.bulkMarkPacked(ids).subscribe({
      next: (res) => this.afterBulk(res, 'marked packed'),
      error: () => {
        this.bulkBusy.set(false);
        this.toasts.error('Bulk mark-packed failed. Please try again.');
      },
    });
  }

  private afterBulk(
    res: { succeeded: number[]; skipped: { id: number; reason: string }[] },
    verb: string,
  ): void {
    this.bulkBusy.set(false);
    const ok = res.succeeded?.length ?? 0;
    const skipped = res.skipped ?? [];
    if (skipped.length === 0) {
      this.toasts.success(`${ok} ${verb}.`);
    } else {
      this.toasts.info(`${ok} ${verb}, ${skipped.length} skipped.`);
    }
    // Reset the current selection, then surface the skipped rows for review.
    this.clearSelection();
    this.lastSkips.set(skipped);
    this.load();
  }

  printLabelsSelected(): void {
    const ids = [...this.selected()];
    if (ids.length === 0 || this.bulkBusy()) {
      return;
    }
    this.bulkBusy.set(true);
    this.service.bulkLabels(ids).subscribe({
      next: (blob) => {
        this.bulkBusy.set(false);
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = 'order-labels.pdf';
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.toasts.success(`Labels generated for ${ids.length} order${ids.length === 1 ? '' : 's'}.`);
      },
      error: () => {
        this.bulkBusy.set(false);
        this.toasts.error('Could not generate labels. Please try again.');
      },
    });
  }

  toggleSkips(): void {
    this.showSkips.update((v) => !v);
  }

  // --- Real-time activity pill (A3) ---------------------------------------

  /** Refetches the current page/filters after a live-activity nudge. */
  refreshFromActivity(): void {
    this.load();
  }

  /** Marks the live feed as seen so the "new activity" pill hides. */
  private acknowledgeActivity(): void {
    const relevant = this.events
      .notifications()
      .filter((n) => n.type === 'ORDER_PACKED' || n.type === 'ORDER_STATUS_CHANGED');
    this.lastSeenActivityTs = relevant.length ? relevant[0].receivedAt.getTime() : 0;
    this.newActivity.set(false);
  }

  // --- Saved views (A4) ---------------------------------------------------

  /** Applies a saved view: sets the filters + sort, then reloads from page 0. */
  applyView(view: SavedView): void {
    this.search.setValue(view.q ?? '', { emitEvent: false });
    // Prefer a stored group; fall back to mapping a legacy raw status onto its group.
    const statusGroup = view.statusGroup ?? groupForStatus(view.status);
    this.filters.setValue(
      {
        statusGroup,
        paymentStatus: view.paymentStatus ?? '',
        from: view.from ?? '',
        to: view.to ?? '',
      },
      { emitEvent: false },
    );
    this.syncActiveStatusGroup();
    this.updateActiveFilterCount();
    this.sort.set(this.parseSort(view.sort));
    this.resetAndLoad();
  }

  /** Opens the inline "name this view" input. */
  beginSaveView(): void {
    this.newViewName.setValue('');
    this.savingView.set(true);
  }

  /** Cancels the inline save-view input. */
  cancelSaveView(): void {
    this.savingView.set(false);
    this.newViewName.setValue('');
  }

  /** Persists the current filter set + sort under the typed name. */
  confirmSaveView(): void {
    const name = this.newViewName.value.trim();
    if (!name) {
      return;
    }
    const f = this.filters.getRawValue();
    const view: SavedView = {
      id: newViewId(),
      name,
      q: this.search.value ?? '',
      status: '',
      statusGroup: f.statusGroup,
      paymentStatus: f.paymentStatus,
      from: f.from,
      to: f.to,
      sort: sortParam(this.sort()),
    };
    const next = [...this.savedViews(), view];
    this.savedViews.set(next);
    persistSavedViews(next);
    this.cancelSaveView();
    this.toasts.success(`Saved view "${name}".`);
  }

  /** Deletes a saved view. */
  deleteView(view: SavedView): void {
    const next = this.savedViews().filter((v) => v.id !== view.id);
    this.savedViews.set(next);
    persistSavedViews(next);
  }

  /** Parses a `field,dir` sort expression into a {@link SortState}. */
  private parseSort(sort: string): SortState {
    const [field, dir] = (sort || 'createdAt,desc').split(',');
    return {
      field: field || 'createdAt',
      dir: (dir === 'asc' ? 'asc' : 'desc') as SortDir,
    };
  }

  // --- Helpers ------------------------------------------------------------

  money(value: Money | undefined): string {
    if (value === undefined || value === null) {
      return '₹0.00';
    }
    return `₹${value}`;
  }

  // --- Detail drawer ------------------------------------------------------

  openDetail(order: OrderSummary): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.detailTab.set('details');
    this.selectedDetail.set(null);
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
    this.service.detail(order.id).subscribe({
      next: (detail) => {
        this.selectedDetail.set(detail);
        this.detailLoading.set(false);
        this.loadScreenshot(detail);
      },
      error: () => {
        this.detailError.set('Could not load this order.');
        this.detailLoading.set(false);
      },
    });
  }

  closeDetail(): void {
    this.selectedDetail.set(null);
    this.detailError.set(null);
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
  }

  /**
   * Fetches the order's payment screenshot as a blob (admin/accountant only) so
   * the reviewer can approve/reject based on the proof of payment. No-op when the
   * role can't view it or the order has no screenshot.
   */
  private loadScreenshot(order: OrderDetail): void {
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
    if (!this.canViewScreenshot()) {
      return;
    }
    // Always attempt the fetch for admin/accountant rather than trusting the
    // `paymentScreenshotAvailable` flag alone: the flag can be stale (e.g. an
    // order whose screenshot was stored under a different storage provider), so
    // we ask the server and let the response decide. A 404 (no screenshot) shows
    // the neutral empty state; any other error shows the "could not load" note.
    this.screenshotLoading.set(true);
    this.service.paymentScreenshot(order.id).subscribe({
      next: (blob) => {
        this.screenshotUrl.set(URL.createObjectURL(blob));
        this.screenshotLoading.set(false);
      },
      error: (err) => {
        // 404 = the order genuinely has no screenshot on file (neutral state).
        this.screenshotMissing.set(err?.status !== 404);
        this.screenshotLoading.set(false);
      },
    });
  }

  /** Revokes and clears any object URL held for the payment screenshot. */
  private revokeScreenshot(): void {
    const url = this.screenshotUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.screenshotUrl.set(null);
  }

  // --- Order-detail presentation helpers (mobile redesign) ---------------

  /** `<img (error)>` fallback for line-item thumbnails → shared placeholder. */
  protected readonly onImageError = imageErrorFallback;

  /**
   * Two-letter initials for the customer avatar chip (we have no customer
   * photos, so the detail view uses an initials chip in Shifa green).
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

  /**
   * Coarse colour group for the status pill: green = completed/delivered,
   * red = cancelled/rejected/failed, amber = everything still in flight
   * (processing steps + pending approval).
   */
  statusPillClass(status: OrderStatus): string {
    if (PILL_COMPLETED.includes(status)) {
      return 'is-green';
    }
    if (PILL_BAD.includes(status)) {
      return 'is-red';
    }
    return 'is-amber';
  }

  /** Human label for the payment authenticity-verification pill (product-audit §4.4). */
  paymentVerificationLabel(status: string | null | undefined): string {
    switch (status) {
      case 'VERIFIED':
        return 'Payment verified';
      case 'REJECTED':
        return 'Payment rejected';
      case 'PENDING':
        return 'Awaiting verification';
      default:
        return '';
    }
  }

  /** Tabler badge tone for the payment-verification pill. */
  paymentVerificationClass(status: string | null | undefined): string {
    switch (status) {
      case 'VERIFIED':
        return 'bg-green-lt';
      case 'REJECTED':
        return 'bg-red-lt';
      case 'PENDING':
        return 'bg-yellow-lt';
      default:
        return 'bg-secondary-lt';
    }
  }

  /** Subtotal = sum of line totals (Money is a decimal string). */
  subtotal(order: OrderDetail): string {
    const sum = (order.items ?? []).reduce((acc, li) => acc + Number(li.lineTotal ?? 0), 0);
    return `₹${sum.toFixed(2)}`;
  }

  /**
   * Thumbnail for an order line item. Resolves the line product's primary image
   * key (supplied by the backend order-detail response) to a URL via
   * {@link resolveImageUrl}; falls back to the shared placeholder when the line
   * has no image key. A broken image swaps to the placeholder via the
   * {@code (error)} handler.
   */
  lineItemThumb(line: OrderDetailLine): string {
    return resolveImageUrl(line.imageKey, PLACEHOLDER_PRODUCT_IMAGE);
  }

  /** Whether the order carries a positive discount (drives the discount totals line). */
  hasDiscount(order: OrderDetail): boolean {
    return Number(order.discountAmount ?? 0) > 0;
  }

  /** The discount formatted as a "- ₹X" reduction for the totals breakdown. */
  discountDisplay(order: OrderDetail): string {
    return `- ₹${Number(order.discountAmount ?? 0).toFixed(2)}`;
  }

  /** Google Maps search deep link for the order's delivery address. */
  mapsUrl(order: OrderDetail): string {
    const query = [order.addressLine, order.city, order.state, order.postalCode]
      .filter(Boolean)
      .join(', ');
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
  }

  downloadInvoice(order: OrderDetail): void {
    this.invoiceLoading.set(true);
    this.invoiceError.set(null);
    this.service.invoice(order.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = `invoice-${order.orderCode}.pdf`;
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.invoiceLoading.set(false);
      },
      error: () => {
        this.invoiceError.set('Could not generate the invoice. Please try again.');
        this.invoiceLoading.set(false);
      },
    });
  }

  /** Whether the acting role may print the packing label (ADMIN only here). */
  canPrintLabel(): boolean {
    return this.auth.hasAnyRole(Role.ADMIN);
  }

  /** Opens the internal packing label PDF (barcode + details) for printing. */
  printLabel(order: OrderDetail): void {
    this.labelLoading.set(true);
    this.service.label(order.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = `label-${order.orderCode}.pdf`;
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.labelLoading.set(false);
      },
      error: () => {
        this.toasts.error('Could not open the label. Please try again.');
        this.labelLoading.set(false);
      },
    });
  }

  // --- Single-order lifecycle action (Req 7.5) ---------------------------

  /**
   * Whether the acting role may approve this order from the detail view. Admin
   * only, and only while the order is awaiting approval. Reuses the existing
   * bulk-approve wiring for a single id — no new backend endpoint (Req 7.5).
   */
  canApprove(order: OrderDetail | null): boolean {
    return (
      !!order &&
      order.orderStatus === OrderStatus.PENDING_ADMIN_APPROVAL &&
      this.auth.hasAnyRole(Role.ADMIN)
    );
  }

  /** Approves the open order (admin, pending only) via the bulk-approve API. */
  async approveOne(order: OrderDetail): Promise<void> {
    if (!this.canApprove(order) || this.detailBusy()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Approve order',
      message: `Approve order ${order.orderCode}?`,
      confirmLabel: 'Approve',
      icon: 'ti-check',
    });
    if (!confirmed) {
      return;
    }
    this.detailBusy.set(true);
    this.service.bulkApprove([order.id]).subscribe({
      next: (res) => {
        this.detailBusy.set(false);
        if (res.succeeded?.includes(order.id)) {
          this.toasts.success(`Order ${order.orderCode} approved.`);
          // Refresh the drawer + list so the new status is reflected.
          this.service.detail(order.id).subscribe((d) => this.selectedDetail.set(d));
          this.load();
        } else {
          const reason = res.skipped?.find((s) => s.id === order.id)?.reason;
          this.toasts.info(reason ? `Skipped: ${reason}` : 'Order could not be approved.');
        }
      },
      error: () => {
        this.detailBusy.set(false);
        this.toasts.error('Could not approve the order. Please try again.');
      },
    });
  }

  // --- Create return (Set B — Feature 2) ---------------------------------

  openCreateReturn(): void {
    this.createReturnError.set(null);
    this.returnForm.reset({ reason: '', notes: '' });
    this.createReturnOpen.set(true);
  }

  closeCreateReturn(): void {
    this.createReturnOpen.set(false);
    this.createReturnError.set(null);
  }

  submitCreateReturn(): void {
    const order = this.selectedDetail();
    if (!order || this.createReturnBusy() || this.returnForm.invalid) {
      this.returnForm.markAllAsTouched();
      return;
    }
    const raw = this.returnForm.getRawValue();
    this.createReturnBusy.set(true);
    this.createReturnError.set(null);
    this.returnsService
      .create({
        orderId: order.id,
        reason: raw.reason.trim(),
        notes: raw.notes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.createReturnBusy.set(false);
          this.closeCreateReturn();
          this.toasts.success(`Return created for ${order.orderCode}.`);
        },
        error: () => {
          this.createReturnBusy.set(false);
          this.createReturnError.set('Could not create the return. Please try again.');
        },
      });
  }
}
