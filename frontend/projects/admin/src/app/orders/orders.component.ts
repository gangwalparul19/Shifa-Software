import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  forkJoin,
  of,
  takeUntil,
} from 'rxjs';
import { RouterLink } from '@angular/router';
import { AuthService, Money, OrderStatus, PaymentStatus, Role, SortDir, SortState } from 'core';
import { OrdersService } from './orders.service';
import {
  CourierCompanyOption,
  DELIVERY_METHOD_OPTIONS,
  DeliveryMethod,
  FAILED_DELIVERY_STATUSES,
  MANUAL_DELIVERY_STAGE_OPTIONS,
  MANUAL_NEXT_STAGES,
  MANUAL_PACKING_STAGES,
  ManualStage,
  OrderDetail,
  OrderDetailLine,
  OrderSummary,
  QuikShipTracking,
  RTO_REASON_LABELS,
  RtoReasonValue,
} from './orders.model';
import { ReturnsService } from '../returns/returns.service';
import { ReturnResponse } from '../returns/returns.model';
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
import { HelpTipComponent } from '../shared/help-tip.component';
import { PaginationComponent } from '../shared/pagination.component';
import { SortableHeaderComponent } from '../shared/sortable-header.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { toggleSort, sortParam } from '../shared/sort.util';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { WHATSAPP_TEMPLATES, openWhatsApp, renderTemplate, whatsAppMessage } from '../shared/whatsapp.util';
import { relativeTime } from '../shared/time.util';
import { WhatsappTemplate, WhatsappTemplatesService } from '../whatsapp/whatsapp-templates.service';
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
  normalizeGroupKey,
  stageLabelForStatus,
} from './order-status-groups';

/** One rendered step in the order-detail visual status timeline. */
interface OrderTimelineStep {
  status: OrderStatusGroupKey;
  label: string;
  icon: string;
  /** Timestamp of the matching status-history row, if one exists yet. */
  at: string | null;
  /** This stage has been reached (a history row exists, or it's an earlier stage than current). */
  done: boolean;
  /** This is the order's present stage. */
  current: boolean;
}

/** Icon per timeline stage, matching the group's business meaning. */
const TIMELINE_ICONS: Record<OrderStatusGroupKey, string> = {
  PENDING_APPROVAL: 'ti-hourglass',
  PROCESSING: 'ti-box',
  SHIPPED: 'ti-truck-delivery',
  DELIVERED: 'ti-circle-check',
  FAILED_RETURNED: 'ti-rotate-2',
  CANCELLED: 'ti-x',
};

/** Friendly label per stage for the timeline (slightly warmer than the filter-tab labels). */
const TIMELINE_LABELS: Record<OrderStatusGroupKey, string> = {
  PENDING_APPROVAL: 'Placed',
  PROCESSING: 'Packed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  FAILED_RETURNED: 'Returned / Failed',
  CANCELLED: 'Cancelled',
};

/** The happy-path stage order (excludes the two terminal-exception groups). */
const HAPPY_PATH: OrderStatusGroupKey[] = ['PENDING_APPROVAL', 'PROCESSING', 'SHIPPED', 'DELIVERED'];

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
  OrderStatus.REDISPATCH,
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
    FormsModule,
    RouterLink,
    DatePipe,
    PageHeaderComponent,
    StatusBadgeComponent,
    StatePanelComponent,
    DensityToggleComponent,
    HelpTipComponent,
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
  private readonly waTemplates = inject(WhatsappTemplatesService);

  /** Whether the current user may punch a new order (SALESPERSON + ADMIN + TEAM_LEAD, Req 7). */
  protected readonly canCreateOrder = computed(() =>
    this.auth.hasAnyRole(Role.SALESPERSON, Role.ADMIN, Role.TEAM_LEAD),
  );

  /** Creating a return is ADMIN-only (Set B — Feature 2, mutations = ADMIN). */
  protected readonly canCreateReturn = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  /**
   * Manually assigning a courier is ADMIN-only, and only useful before the
   * order has actually been dispatched (terminal/late-stage orders already
   * have a real courier, or never will).
   */
  canAssignCourier(order: OrderDetail | null): boolean {
    if (!order || !this.auth.hasAnyRole(Role.ADMIN)) {
      return false;
    }
    const late: (OrderStatus | string)[] = [
      OrderStatus.DISPATCHED,
      OrderStatus.IN_TRANSIT,
      OrderStatus.OUT_FOR_DELIVERY,
      OrderStatus.DELIVERED,
      OrderStatus.COD_COLLECTED,
      OrderStatus.CLOSED,
      OrderStatus.REJECTED,
      OrderStatus.CANCELLED,
    ];
    return !late.includes(order.orderStatus);
  }

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
  protected readonly bulkPreviewLoading = signal(false);
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

  /**
   * One-tap WhatsApp message templates for the order detail drawer. Loaded from
   * the server-managed set (V44) on init; falls back to the built-in defaults
   * until they arrive (or if the request fails).
   */
  protected readonly whatsappTemplates = signal<WhatsappTemplate[] | typeof WHATSAPP_TEMPLATES>(
    WHATSAPP_TEMPLATES,
  );

  /** Loads the active server-managed WhatsApp templates (non-fatal on error). */
  private loadWhatsappTemplates(): void {
    this.waTemplates.active().subscribe({
      next: (list) => {
        if (list && list.length > 0) {
          this.whatsappTemplates.set(list);
        }
      },
      error: () => {
        /* keep built-in defaults */
      },
    });
  }

  /**
   * Loads the known delivery partners for the "Assign courier" dropdown
   * (delivery-partner dropdown enhancement; non-fatal on error — the modal
   * falls back to a free-text "other" input only).
   */
  private loadCourierCompanies(): void {
    this.service.courierCompanies().subscribe({
      next: (list) => this.courierCompanies.set(list ?? []),
      error: () => {
        /* fall back to free-text only */
      },
    });
  }

  /**
   * The courier name to display for an order's shipment card: the actual
   * assigned delivery partner when known, else "In-House" for in-house delivery
   * orders, else "QuikShipX" as the default delivery method (never blank/absent
   * per the client's request).
   */
  courierDisplayName(order: OrderDetail): string {
    if (order.courierName) {
      return order.courierName;
    }
    return this.isInHouse(order) ? 'In-House' : 'QuikShipX';
  }

  /** Whether this order is fulfilled by Shifa's own team (no courier partner, no AWB). */
  isInHouse(order: OrderDetail | null): boolean {
    return !!order && order.deliveryMethod === 'IN_HOUSE';
  }

  // --- In-house manual delivery status (in-house-delivery feature) ----------

  /** The stage picked in the status control, pending confirmation. */
  protected readonly manualStage = signal<ManualStage | ''>('');
  protected readonly manualVehicle = signal('');
  protected readonly manualNote = signal('');
  protected readonly manualStatusBusy = signal(false);

  setManualStage(value: string): void {
    this.manualStage.set((value || '') as ManualStage | '');
  }

  setManualVehicle(value: string): void {
    this.manualVehicle.set(value ?? '');
  }

  setManualNote(value: string): void {
    this.manualNote.set(value ?? '');
  }

  /**
   * The statuses this order can legally be moved to by hand right now, filtered
   * by what the caller's role is actually allowed to do:
   * <ul>
   *   <li>the warehouse steps (Packed / Handed to delivery) apply to ANY order
   *       and need ADMIN or PACKING_USER — they mirror the Packing page;</li>
   *   <li>the post-handover delivery stages are in-house only (a courier reports
   *       its own progress) and additionally allow the order's salesperson.</li>
   * </ul>
   * Empty when the order has no manual next step (terminal, or awaiting approval).
   */
  allowedManualStages(order: OrderDetail | null): typeof MANUAL_DELIVERY_STAGE_OPTIONS {
    if (!order) {
      return [];
    }
    const next = MANUAL_NEXT_STAGES[String(order.orderStatus)] ?? [];
    const isWarehouseStaff = this.auth.hasAnyRole(Role.ADMIN, Role.PACKING_USER);
    const isSalesperson = this.auth.hasAnyRole(Role.SALESPERSON);
    return MANUAL_DELIVERY_STAGE_OPTIONS.filter((opt) => {
      if (!next.includes(opt.value)) {
        return false;
      }
      if (MANUAL_PACKING_STAGES.includes(opt.value)) {
        return isWarehouseStaff;
      }
      // Post-handover stages: in-house orders only.
      return this.isInHouse(order) && (isWarehouseStaff || isSalesperson);
    });
  }

  /** Whether the manual status control should be offered at all. */
  canUpdateDeliveryStatus(order: OrderDetail | null): boolean {
    return this.allowedManualStages(order).length > 0;
  }

  /** The hint for the currently picked stage, for the inline explanation line. */
  manualStageHint(): string | null {
    const stage = this.manualStage();
    return MANUAL_DELIVERY_STAGE_OPTIONS.find((o) => o.value === stage)?.hint ?? null;
  }

  /**
   * Whether a delivery attempt on this order has failed or been refused — the parcel
   * is still in hand, so it can be retried, or closed out by marking it RTO.
   */
  hasFailedDelivery(order: OrderDetail | null): boolean {
    return !!order && FAILED_DELIVERY_STATUSES.includes(String(order.orderStatus));
  }

  /** Applies the picked stage (and, for in-house, the vehicle no.) to the order. */
  updateDeliveryStatus(order: OrderDetail): void {
    const stage = this.manualStage();
    if (!stage || this.manualStatusBusy()) {
      return;
    }
    if ((stage === 'CUSTOMER_REJECTED' || stage === 'DELIVERY_FAILED') && !this.manualNote().trim()) {
      this.toasts.error('Add a short reason before marking this delivery failed or refused.');
      return;
    }
    this.manualStatusBusy.set(true);
    this.service
      .updateDeliveryStatus(order.id, stage, {
        vehicleNumber: this.manualVehicle(),
        note: this.manualNote().trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.manualStatusBusy.set(false);
          this.manualStage.set('');
          this.selectedDetail.set(updated);
          this.toasts.success(
            `${order.orderCode} updated to ${humanizeStatus(updated.orderStatus)}.`,
          );
          this.load();
        },
        error: (err: { error?: { message?: string } }) => {
          this.manualStatusBusy.set(false);
          this.toasts.error(this.concurrencyMessage(err, 'Could not update the delivery status. Please try again.'));
        },
      });
  }

  /** Direct follow-up action from an orders-list row (without opening the drawer). */
  sendWhatsAppSummary(order: OrderSummary, event?: Event): void {
    event?.stopPropagation();
    const message = whatsAppMessage('confirm', {
      customerName: order.customerName,
      orderCode: order.orderCode,
      total: order.totalAmount,
    });
    if (!openWhatsApp(order.customerMobile, message)) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }

  /** Prevents a contact button from opening the order drawer as well. */
  stopRowClick(event: Event): void {
    event.stopPropagation();
  }

  /** Opens the customer's phone app from an order-list row. */
  callCustomer(mobile: string, event?: Event): void {
    event?.stopPropagation();
    window.location.href = `tel:${mobile}`;
  }

  toggleRawHistory(): void {
    this.rawHistoryOpen.update((value) => !value);
  }

  /** Opens WhatsApp for the order's customer with a pre-filled template message. */
  sendWhatsApp(order: OrderDetail, key: string): void {
    const ctx = {
      customerName: order.customerName,
      orderCode: order.orderCode,
      total: order.totalAmount,
      remaining: order.remainingAmount,
      paid: order.amountReceived,
      items: (order.items ?? []).map((li) => ({
        name: li.productName,
        quantity: li.quantity,
        lineTotal: li.lineTotal,
      })),
    };
    const tpl = this.whatsappTemplates().find((t) => t.key === key);
    const message = tpl ? renderTemplate(tpl.body, ctx) : whatsAppMessage(key, ctx);
    const ok = openWhatsApp(order.customerMobile, message);
    if (!ok) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }

  // --- Detail drawer ------------------------------------------------------
  protected readonly selectedDetail = signal<OrderDetail | null>(null);
  /**
   * Active tab in the order detail drawer so its (long) content is split into
   * Details / Items / Payment tabs instead of one long scroll. The action
   * buttons stay pinned below the tabs, visible from any tab.
   */
  protected readonly detailTab = signal<'details' | 'items' | 'payment'>('details');
  protected readonly rawHistoryOpen = signal(false);
  protected readonly canViewRawHistory = computed(() => this.auth.hasAnyRole(Role.ADMIN));
  protected readonly detailLoading = signal(false);
  /** Existing returns for the open order (closes the loop between Returns and Orders). */
  protected readonly orderReturns = signal<ReturnResponse[]>([]);

  /**
   * The delivery method picked in the detail drawer for the order awaiting
   * approval, defaulting to the order's own current value (in-house-delivery
   * feature: the admin decides/overrides the delivery partner at approval) —
   * mirrors the same picker on the Approval Queue page so it's available from
   * the Orders page too.
   */
  protected readonly deliveryMethod = signal<DeliveryMethod>('IN_HOUSE');
  protected readonly deliveryMethodOptions = DELIVERY_METHOD_OPTIONS;

  setDeliveryMethod(value: string): void {
    this.deliveryMethod.set(value === 'QUIKSHIPX' ? 'QUIKSHIPX' : 'IN_HOUSE');
  }
  protected readonly detailError = signal<string | null>(null);
  protected readonly invoiceLoading = signal(false);
  protected readonly invoiceError = signal<string | null>(null);
  protected readonly labelLoading = signal(false);
  /** Busy flag for single-order lifecycle actions in the detail drawer. */
  protected readonly detailBusy = signal(false);

  // --- Payment screenshots (admin/accountant review) ----------------------
  /**
   * Object URLs of every payment proof fetched for the open order, in upload
   * order (V65). An order may have several — a part payment plus the balance, or
   * a UPI receipt plus a bank confirmation — so the reviewer sees all of them.
   */
  protected readonly screenshotUrls = signal<string[]>([]);
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

  // --- Manual courier/AWB assignment ("assign courier early" enhancement) ---
  /** Whether the assign-courier modal is open for the current detail order. */
  protected readonly assignCourierOpen = signal(false);
  protected readonly assignCourierBusy = signal(false);
  protected readonly assignCourierError = signal<string | null>(null);
  protected readonly assignCourierForm = new FormGroup({
    courierName: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(150)],
    }),
    awb: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(64)],
    }),
  });

  /** Sentinel select value meaning "type a partner not in the list". */
  protected readonly OTHER_COURIER = '__OTHER__';
  /** Known delivery partners for the "Assign courier" dropdown (delivery-partner dropdown enhancement). */
  protected readonly courierCompanies = signal<CourierCompanyOption[]>([]);
  /** Whether the free-text "other partner" input is shown (sentinel selected, or a name not in the list). */
  protected readonly showOtherCourierInput = signal(false);
  /** The dropdown's own current selection (may be the sentinel value, distinct from the actual courierName). */
  protected readonly courierSelectValue = signal('');

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
    this.loadWhatsappTemplates();
    this.loadCourierCompanies();
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
    const statusGroup = q
      ? ''
      : (normalizeGroupKey(qp.get('statusGroup')) || groupForStatus(qp.get('status')));
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

  /**
   * Adds every currently-loaded order whose {@code createdAt} falls in the given
   * date bucket to the selection (Today / Yesterday / This week / This month /
   * All on page). Operates on the loaded page (like select-all-on-page); the
   * backend authorises + skips ineligible rows when a bulk action runs.
   */
  selectByDate(bucket: 'today' | 'yesterday' | 'week' | 'month' | 'all'): void {
    const rows = this.orders();
    this.selected.update((set) => {
      const next = new Set(set);
      rows.forEach((o) => {
        if (bucket === 'all' || this.matchesDateBucket(o.createdAt, bucket)) {
          next.add(o.id);
        }
      });
      return next;
    });
  }

  /** Whether an ISO timestamp falls within the named date bucket (local time). */
  private matchesDateBucket(iso: string | undefined, bucket: string): boolean {
    if (!iso) {
      return false;
    }
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return false;
    }
    const now = new Date();
    const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate());
    const today = startOfDay(now);
    const dDay = startOfDay(d);
    switch (bucket) {
      case 'today':
        return dDay.getTime() === today.getTime();
      case 'yesterday': {
        const y = new Date(today);
        y.setDate(y.getDate() - 1);
        return dDay.getTime() === y.getTime();
      }
      case 'week': {
        // Current calendar week starting Monday.
        const dayFromMon = (today.getDay() + 6) % 7;
        const monday = new Date(today);
        monday.setDate(today.getDate() - dayFromMon);
        return d.getTime() >= monday.getTime();
      }
      case 'month':
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
      default:
        return true;
    }
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

  approveSelected(): void {
    const ids = [...this.selected()];
    this.previewThenConfirm('APPROVE', ids, 'Approve', (eligible) => {
      this.bulkBusy.set(true);
      this.service.bulkApprove(eligible).subscribe({
        next: (res) => this.afterBulk(res, 'approved'),
        error: (error) => {
          this.bulkBusy.set(false);
          this.toasts.error(this.concurrencyMessage(error, 'Bulk approve failed. Refresh and try again.'));
        },
      });
    });
  }

  markPackedSelected(): void {
    const ids = [...this.selected()];
    this.previewThenConfirm('MARK_PACKED', ids, 'Mark packed', (eligible) => {
      this.bulkBusy.set(true);
      this.service.bulkMarkPacked(eligible).subscribe({
        next: (res) => this.afterBulk(res, 'marked packed'),
        error: (error) => {
          this.bulkBusy.set(false);
          this.toasts.error(this.concurrencyMessage(error, 'Bulk mark-packed failed. Refresh and try again.'));
        },
      });
    });
  }

  private previewThenConfirm(
    action: 'APPROVE' | 'MARK_PACKED' | 'LABELS',
    ids: number[],
    verb: string,
    proceed: (eligible: number[]) => void,
  ): void {
    if (ids.length === 0 || this.bulkBusy() || this.bulkPreviewLoading()) {
      return;
    }
    this.bulkPreviewLoading.set(true);
    this.service.bulkPreview(action, ids).subscribe({
      next: async (preview) => {
        this.bulkPreviewLoading.set(false);
        const eligible = (preview.eligible ?? []).map((item) => item.id);
        const skipped = preview.ineligible ?? [];
        if (eligible.length === 0) {
          this.toasts.info('Nothing is eligible for this action. Refresh the list and try again.');
          this.lastSkips.set(skipped.map((item) => ({ id: item.id, reason: item.reason ?? 'Not eligible.' })));
          this.showSkips.set(true);
          return;
        }
        const skippedText = skipped.length
          ? ` ${skipped.length} will be skipped because their status changed or they are unavailable.`
          : '';
        const confirmed = await this.confirm.confirm({
          title: `${verb} selected orders`,
          message: `${eligible.length} of ${ids.length} selected order${ids.length === 1 ? '' : 's'} are eligible.${skippedText} The server will re-check each order before applying the action.`,
          confirmLabel: verb,
          icon: action === 'APPROVE' ? 'ti-check' : action === 'MARK_PACKED' ? 'ti-package' : 'ti-printer',
        });
        if (confirmed) {
          proceed(eligible);
        }
      },
      error: () => {
        this.bulkPreviewLoading.set(false);
        this.toasts.error('Could not preview the selected orders. Refresh and try again.');
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
    this.previewThenConfirm('LABELS', ids, 'Print labels', (eligible) => {
      this.bulkBusy.set(true);
      this.service.bulkLabels(eligible).subscribe({
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
          this.toasts.success(`Labels generated for ${eligible.length} order${eligible.length === 1 ? '' : 's'}.`);
          this.clearSelection();
          this.load();
        },
        error: (error) => {
          this.bulkBusy.set(false);
          this.toasts.error(this.concurrencyMessage(error, 'Could not generate labels. Refresh and try again.'));
        },
      });
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
    // Prefer a stored group (normalised for pre-collapse keys); fall back to mapping a legacy raw status onto its group.
    const statusGroup = normalizeGroupKey(view.statusGroup) || groupForStatus(view.status);
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

  private concurrencyMessage(error: unknown, fallback: string): string {
    const err = error as HttpErrorResponse | undefined;
    if (err?.status === 409 || (err?.error as { code?: string } | undefined)?.code === 'CONCURRENT_UPDATE') {
      return 'This order changed elsewhere. Refresh the order and try again.';
    }
    return (err?.error as { message?: string } | undefined)?.message ?? fallback;
  }

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
    this.rawHistoryOpen.set(false);
    this.selectedDetail.set(null);
    this.quikShipTracking.set(null);
    this.orderReturns.set([]);
    // Reset the in-house manual status controls so nothing carries over between orders.
    this.manualStage.set('');
    this.manualVehicle.set('');
    this.manualNote.set('');
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
    this.service.detail(order.id).subscribe({
      next: (detail) => {
        this.selectedDetail.set(detail);
        this.detailLoading.set(false);
        this.deliveryMethod.set(detail.deliveryMethod ?? 'IN_HOUSE');
        // Pre-fill the in-house vehicle field with whatever is already recorded.
        this.manualVehicle.set(detail.vehicleNumber ?? '');
        this.loadScreenshot(detail);
        // Pull live courier tracking (status + timeline) when published to QuikShipX.
        this.loadTracking(detail, { silent: true });
        this.loadOrderReturns(detail.id);
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
    this.quikShipTracking.set(null);
    this.orderReturns.set([]);
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
  }

  /**
   * Loads existing returns for the open order (non-fatal on error — the
   * backend gates {@code /api/admin/returns/*} to ADMIN/ACCOUNTANT/CA, so a
   * salesperson viewing the drawer simply sees no returns section rather than
   * an error).
   */
  private loadOrderReturns(orderId: number): void {
    if (!this.auth.hasAnyRole(Role.ADMIN, Role.ACCOUNTANT)) {
      return;
    }
    this.returnsService.byOrder(orderId).subscribe({
      next: (returns) => this.orderReturns.set(returns),
      error: () => this.orderReturns.set([]),
    });
  }

  /**
   * Fetches EVERY payment proof for the order as blobs (admin/accountant only) so
   * the reviewer can approve/reject against all the proof on file (V65). No-op
   * when the role can't view them.
   *
   * <p>Enumerates the proofs first, then fetches each one. A proof whose bytes
   * cannot be loaded is skipped rather than failing the whole set, so one broken
   * object never hides the others. If the listing itself fails we fall back to the
   * legacy single-proof endpoint, which keeps the drawer working against an order
   * whose proofs predate V65.
   */
  private loadScreenshot(order: OrderDetail): void {
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
    if (!this.canViewScreenshot()) {
      return;
    }
    // Always ask the server rather than trusting the `paymentScreenshotAvailable`
    // flag alone: the flag can be stale (e.g. an order whose screenshot was stored
    // under a different storage provider), so we let the response decide. No proofs
    // shows the neutral empty state; a genuine failure shows "could not load".
    this.screenshotLoading.set(true);
    this.service.paymentScreenshots(order.id).subscribe({
      next: (shots) => {
        if (shots.length === 0) {
          this.screenshotLoading.set(false);
          return;
        }
        forkJoin(
          shots.map((shot) =>
            this.service
              .paymentScreenshotById(order.id, shot.id)
              .pipe(catchError(() => of(null))),
          ),
        ).subscribe((blobs) => {
          const urls = blobs
            .filter((blob): blob is Blob => blob !== null)
            .map((blob) => URL.createObjectURL(blob));
          this.screenshotUrls.set(urls);
          // Every proof was listed but none could be fetched — a real failure.
          this.screenshotMissing.set(urls.length === 0);
          this.screenshotLoading.set(false);
        });
      },
      error: () => this.loadLegacyScreenshot(order),
    });
  }

  /**
   * Fallback to the pre-V65 single-proof endpoint when the proof listing is
   * unavailable, so the drawer still shows the primary screenshot.
   */
  private loadLegacyScreenshot(order: OrderDetail): void {
    this.service.paymentScreenshot(order.id).subscribe({
      next: (blob) => {
        this.screenshotUrls.set([URL.createObjectURL(blob)]);
        this.screenshotLoading.set(false);
      },
      error: (err) => {
        // 404 = the order genuinely has no screenshot on file (neutral state).
        this.screenshotMissing.set(err?.status !== 404);
        this.screenshotLoading.set(false);
      },
    });
  }

  /** Revokes and clears every object URL held for the payment proofs. */
  private revokeScreenshot(): void {
    for (const url of this.screenshotUrls()) {
      URL.revokeObjectURL(url);
    }
    this.screenshotUrls.set([]);
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

  /** Human label for a manually-marked RTO reason (label redesign feature). */
  rtoReasonLabel(reason: RtoReasonValue | null | undefined): string {
    return reason ? RTO_REASON_LABELS[reason] ?? reason : '';
  }

  /** Short relative time (enhancement: relative timestamps) — shown alongside the exact date, not instead of it. */
  protected readonly relativeTime = relativeTime;

  /**
   * Builds the order-detail visual status timeline (enhancement: order status
   * timeline) — a friendly "Placed → Packed → Shipped → Delivered" stepper with
   * real timestamps drawn from the order's status-history rows, folding the many
   * raw statuses into the same business-facing groups the Orders page filter
   * uses (so a less-technical salesperson reads one consistent vocabulary).
   *
   * <p>Cancelled/Failed-Returned orders replace the happy path's tail with their
   * own single terminal step, since those are exceptions to (not steps on) the
   * normal delivery journey.
   */
  orderTimelineSteps(order: OrderDetail): OrderTimelineStep[] {
    const currentGroup = groupForStatus(order.orderStatus);
    const history = order.statusHistory ?? [];

    // Earliest changedAt whose toStatus falls in each group (oldest first data,
    // but be defensive and take the min just in case).
    const firstAtByGroup = new Map<OrderStatusGroupKey, string>();
    for (const row of history) {
      const group = groupForStatus(row.toStatus);
      if (!group) {
        continue;
      }
      const existing = firstAtByGroup.get(group);
      if (!existing || new Date(row.changedAt).getTime() < new Date(existing).getTime()) {
        firstAtByGroup.set(group, row.changedAt);
      }
    }

    // Exception path: cancelled or failed/returned — show Placed then the
    // terminal exception step only (no fabricated "Shipped"/"Delivered" steps
    // that never happened for a cancelled order).
    if (currentGroup === 'CANCELLED' || currentGroup === 'FAILED_RETURNED') {
      const placedAt = firstAtByGroup.get('PENDING_APPROVAL') ?? order.createdAt ?? null;
      const terminalAt = firstAtByGroup.get(currentGroup) ?? null;
      return [
        this.buildStep('PENDING_APPROVAL', placedAt, true, false),
        this.buildStep(currentGroup, terminalAt, true, true),
      ];
    }

    const currentIndex = HAPPY_PATH.indexOf(currentGroup || 'PENDING_APPROVAL');
    return HAPPY_PATH.map((stage, i) => {
      const at = stage === 'PENDING_APPROVAL' && !firstAtByGroup.has(stage)
        ? order.createdAt ?? null
        : firstAtByGroup.get(stage) ?? null;
      return this.buildStep(stage, at, i <= currentIndex, i === currentIndex);
    });
  }

  private buildStep(
    status: OrderStatusGroupKey,
    at: string | null,
    done: boolean,
    current: boolean,
  ): OrderTimelineStep {
    return {
      status,
      label: TIMELINE_LABELS[status],
      icon: TIMELINE_ICONS[status],
      at,
      done,
      current,
    };
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

  /**
   * Whether the acting role may edit this order's details (edit-order
   * feature). Admin only, and only while the order hasn't moved past the
   * editable statuses (mirrors the server-side {@code OrderNotEditableException}
   * guard, so this is a UI convenience — the server enforces it regardless).
   */
  canEditOrder(order: OrderDetail | null): boolean {
    return (
      !!order &&
      (order.orderStatus === OrderStatus.PENDING_ADMIN_APPROVAL ||
        order.orderStatus === OrderStatus.APPROVED) &&
      this.auth.hasAnyRole(Role.ADMIN)
    );
  }

  /**
   * Approves the open order (admin, pending only), carrying the delivery
   * method picked in the drawer (defaults to the order's own current value,
   * i.e. In-house — mirrors the Approval Queue page's picker).
   */
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
    this.service.approve(order.id, this.deliveryMethod()).subscribe({
      next: (updated) => {
        this.detailBusy.set(false);
        this.toasts.success(`Order ${order.orderCode} approved.`);
        this.selectedDetail.set(updated);
        this.load();
      },
      error: () => {
        this.detailBusy.set(false);
        this.toasts.error('Could not approve the order. Please try again.');
      },
    });
  }

  // --- QuikShipX shipment (courier integration) --------------------------

  /** Busy flag for the QuikShipX publish action in the detail drawer. */
  protected readonly quikShipBusy = signal(false);

  /** Whether the current user may (re)publish an order to QuikShipX (ADMIN). */
  protected readonly canManageQuikShip = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  /** Live QuikShipX tracking (status + scan timeline) for the open order. */
  protected readonly quikShipTracking = signal<QuikShipTracking | null>(null);
  protected readonly trackLoading = signal(false);

  /**
   * Fetches live QuikShipX tracking for the open order (current status + scan
   * timeline) and refreshes the internal status server-side. No-op when the order
   * has no shipment yet. Called on drawer open and by the manual refresh button.
   */
  loadTracking(order: OrderDetail | null, opts: { silent?: boolean } = {}): void {
    if (!order || !order.quikShipXStatus) {
      this.quikShipTracking.set(null);
      return;
    }
    this.trackLoading.set(true);
    this.service.quikShipTrack(order.id).subscribe({
      next: (t) => {
        this.quikShipTracking.set(t);
        this.trackLoading.set(false);
      },
      error: () => {
        this.trackLoading.set(false);
        if (!opts.silent) {
          this.toasts.error('Could not fetch tracking from QuikShipX.');
        }
      },
    });
  }

  /** Hover title for the Orders-list QuikShipX chip: status + order id + AWB. */
  quikShipTitle(order: OrderSummary): string {
    const parts = ['QuikShipX ' + (order.quikShipXStatus ?? '')];
    if (order.quikShipXOrderId) {
      parts.push('order #' + order.quikShipXOrderId);
    }
    if (order.quikShipXAwb) {
      parts.push('AWB ' + order.quikShipXAwb);
    }
    return parts.join(' · ');
  }

  /** Copies text (QuikShipX order id / AWB) to the clipboard with a toast. */
  async copyText(text: string | null | undefined, label: string): Promise<void> {
    if (!text) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      this.toasts.success(`${label} copied`);
    } catch {
      this.toasts.error('Could not copy to clipboard.');
    }
  }

  /**
   * Whether to show the friendly business-stage label (e.g. "Shipped") instead of
   * the raw internal status on the Orders list — on for salespeople, who found the
   * raw courier statuses confusing; admins/accountants/packers keep the precise
   * status badge.
   */
  protected readonly useStageLabel = computed(() => this.auth.hasAnyRole(Role.SALESPERSON));

  /** The friendly business-stage label for a raw order status (Change 2). */
  stageLabel(status: OrderStatus): string {
    return stageLabelForStatus(status);
  }

  /** Tabler badge class for the friendly stage label, coloured by lifecycle stage. */
  stageBadgeClass(status: OrderStatus): string {
    switch (groupForStatus(status)) {
      case 'DELIVERED':
        return 'badge bg-green-lt';
      case 'SHIPPED':
        return 'badge bg-blue-lt';
      case 'PROCESSING':
        return 'badge bg-cyan-lt';
      case 'FAILED_RETURNED':
        return 'badge bg-red-lt';
      case 'CANCELLED':
        return 'badge bg-secondary-lt';
      default:
        return 'badge bg-yellow-lt';
    }
  }

  /** Tabler badge class for a QuikShipX status label (colour by lifecycle stage). */
  quikShipBadgeClass(status: string | null | undefined): string {
    const s = (status ?? '').toLowerCase();
    if (s.includes('deliver')) {
      return 'tone-green';
    }
    if (s.includes('return') || s.includes('lost') || s.includes('cancel')) {
      return 'tone-red';
    }
    if (s.includes('transit') || s.includes('out for') || s.includes('pickup')) {
      return 'tone-blue';
    }
    if (s.includes('tracking') || s.includes('label') || s.includes('confirm')) {
      return 'tone-blue';
    }
    return 'tone-amber';
  }



  /** (Re)queues the open order for publication to QuikShipX (ADMIN). */
  publishToQuikShip(order: OrderDetail): void {
    if (!this.canManageQuikShip() || this.quikShipBusy()) {
      return;
    }
    this.quikShipBusy.set(true);
    this.service.quikShipPublish(order.id).subscribe({
      next: (ack) => {
        this.quikShipBusy.set(false);
        if (ack.queued) {
          this.toasts.success(ack.message);
        } else {
          this.toasts.info(ack.message);
        }
      },
      error: () => {
        this.quikShipBusy.set(false);
        this.toasts.error('Could not queue the order for QuikShipX. Please try again.');
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
        orderId: String(order.id),
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

  // --- Manual courier/AWB assignment ("assign courier early" enhancement) ---

  openAssignCourier(order: OrderDetail): void {
    this.assignCourierError.set(null);
    const currentName = order.courierName ?? '';
    const knownNames = this.courierCompanies().map((c) => c.name);
    // Pre-select the current courier in the dropdown when it's a known partner;
    // otherwise default to "In-House" (or fall back to the free-text "other"
    // input pre-filled with the unrecognised name).
    const isKnown = currentName !== '' && knownNames.includes(currentName);
    const isOther = currentName !== '' && !isKnown;
    this.showOtherCourierInput.set(isOther);
    this.courierSelectValue.set(isOther ? this.OTHER_COURIER : currentName || 'In-House');
    this.assignCourierForm.reset({
      courierName: isOther ? currentName : isKnown ? currentName : 'In-House',
      awb: order.awb ?? '',
    });
    this.syncAwbRequirement();
    this.assignCourierOpen.set(true);
  }

  closeAssignCourier(): void {
    this.assignCourierOpen.set(false);
    this.assignCourierError.set(null);
  }

  /** The dropdown's change handler: toggles the free-text "other partner" input. */
  onCourierSelectChange(value: string): void {
    this.courierSelectValue.set(value);
    if (value === this.OTHER_COURIER) {
      this.showOtherCourierInput.set(true);
      this.assignCourierForm.controls.courierName.setValue('');
    } else {
      this.showOtherCourierInput.set(false);
      this.assignCourierForm.controls.courierName.setValue(value);
    }
    this.syncAwbRequirement();
  }

  /**
   * Whether an AWB must be supplied: only for a real courier partner. An
   * In-House delivery has no tracking number at all, so the field is optional
   * there (in-house-delivery feature) — the label then prints our own order
   * barcode, which the packing/RTO scan resolves.
   */
  awbRequired(): boolean {
    return this.courierSelectValue() !== 'In-House';
  }

  /** Applies/removes the AWB required-validator to match the selected partner. */
  private syncAwbRequirement(): void {
    const awb = this.assignCourierForm.controls.awb;
    awb.setValidators(
      this.awbRequired()
        ? [Validators.required, Validators.maxLength(64)]
        : [Validators.maxLength(64)],
    );
    awb.updateValueAndValidity({ emitEvent: false });
  }

  submitAssignCourier(): void {
    const order = this.selectedDetail();
    if (!order || this.assignCourierBusy() || this.assignCourierForm.invalid) {
      this.assignCourierForm.markAllAsTouched();
      return;
    }
    const raw = this.assignCourierForm.getRawValue();
    this.assignCourierBusy.set(true);
    this.assignCourierError.set(null);
    this.service.assignCourier(order.id, raw.courierName.trim(), raw.awb.trim()).subscribe({
      next: () => {
        this.assignCourierBusy.set(false);
        this.closeAssignCourier();
        this.toasts.success(`Courier assigned for ${order.orderCode}.`);
        // Refresh the open drawer so the shipment card + label barcode reflect
        // the newly-assigned courier/AWB immediately.
        this.service.detail(order.id).subscribe((updated) => this.selectedDetail.set(updated));
      },
      error: () => {
        this.assignCourierBusy.set(false);
        this.assignCourierError.set('Could not assign the courier. Please try again.');
      },
    });
  }
}
