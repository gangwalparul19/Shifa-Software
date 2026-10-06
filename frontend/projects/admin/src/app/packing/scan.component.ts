import { IstDatePipe } from '../shared/ist-date.pipe';
import { AfterViewInit, Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { ApiError } from 'core';
import { DashboardService } from '../dashboard/dashboard.service';
import { PackingService } from './packing.service';
import { PackingQueueRow, PackingScanResponse, ScanLogEntry, ScanOutcome } from './packing.model';
import { MANUAL_DELIVERY_STAGE_OPTIONS } from '../orders/orders.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import {
  SourceFilterMode,
  matchesSourceMode,
  readSourceFilter,
  writeSourceFilter,
} from '../shared/source-filter.util';
import { InrPipe } from '../shared/inr.pipe';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { ChannelLogoComponent } from '../shared/channel-logo.component';
import { ToastService } from '../shared/toast.service';
import { NoteCellComponent } from '../shared/note-cell.component';
import { CameraScannerComponent } from './camera-scanner.component';

/** The current banner shown above the input after a scan. */
interface ScanBanner {
  outcome: ScanOutcome;
  title: string;
  detail: string;
}

/** A count card describing an awaiting-* queue for the packer/admin (Req 9.4, 10.1). */
interface QueueCard {
  label: string;
  value: number;
  icon: string;
}

/** The workflow phase of an order the packer is actively moving through. */
type WorkPhase = 'packed' | 'handed_over' | 'dispatched' | 'other';

/**
 * An order the packer has just acted on, tracked in-session so Handover /
 * Dispatch can be driven inline off the scan flow (Req 9.2–9.4, 10.1).
 */
interface PackWorkItem {
  id: number;
  orderCode: string;
  customerName: string;
  status: string;
  phase: WorkPhase;
  busy: boolean;
  error: string | null;
}

/**
 * Packing barcode-scan view (Req 11.1, 11.3, 11.4).
 *
 * <p>A large, autofocused barcode field submits on Enter — so it works both with
 * a handheld scanner (which types the code then sends Enter) and with manual
 * typing. Each submit calls {@code POST /api/packing/scan} and shows a clear
 * result banner:
 * <ul>
 *   <li><strong>packed</strong>: "Order &lt;code&gt; marked Packed" (Req 11.1);</li>
 *   <li><strong>not recognized</strong>: barcode not recognized (Req 11.3);</li>
 *   <li><strong>wrong status</strong>: "cannot pack — order is &lt;status&gt;"
 *       (Req 11.4).</li>
 * </ul>
 * A running list of recent scans in the session is kept below the input, and the
 * field is cleared and refocused after every scan for rapid back-to-back use.
 */
@Component({
  selector: 'admin-packing-scan',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    IstDatePipe,
    PageHeaderComponent,
    PaginationComponent,
    StatusBadgeComponent,
    CameraScannerComponent,
    InrPipe,
    ChannelLogoComponent,
    NoteCellComponent,
  ],
  templateUrl: './scan.component.html',
  styleUrl: './scan.component.css',
})
export class ScanComponent implements OnInit, AfterViewInit {
  private readonly service = inject(PackingService);
  private readonly dashboard = inject(DashboardService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);

  @ViewChild('barcodeInput') private barcodeInput?: ElementRef<HTMLInputElement>;

  protected readonly form = this.fb.nonNullable.group({
    barcode: ['', [Validators.required]],
  });

  protected readonly submitting = signal(false);
  protected readonly banner = signal<ScanBanner | null>(null);

  /** The dismissible "how packing works" strip — hidden once dismissed (per browser). */
  protected readonly showGuide = signal(
    typeof localStorage === 'undefined' || localStorage.getItem('shifa:packing-guide-dismissed') !== '1',
  );
  dismissGuide(): void {
    this.showGuide.set(false);
    try {
      localStorage.setItem('shifa:packing-guide-dismissed', '1');
    } catch {
      /* ignore storage errors (private mode) */
    }
  }

  /** Whether the phone-camera scanner overlay is open (FEATURE-ROADMAP §8.2). */
  protected readonly cameraOpen = signal(false);
  protected readonly log = signal<ScanLogEntry[]>([]);

  /** Orders the packer is actively moving through handover / dispatch this session. */
  protected readonly workItems = signal<PackWorkItem[]>([]);

  // --- Awaiting-handover / awaiting-dispatch context (Req 9.4, 10.1) ------
  private readonly queueSummary = signal<QueueCard[]>([]);

  /** The awaiting-* queue cards surfaced above the scan area. */
  protected readonly queues = computed<QueueCard[]>(() => this.queueSummary());

  // --- Source filter (All / Portal / Shopify) -----------------------------
  /**
   * The order-source filter for every queue on this page. "Portal" (the default)
   * means every non-Shopify order; "Shopify" means only Shopify-imported orders.
   * The choice is persisted per browser so the packer's view sticks across
   * reloads. Filtering is client-side (the queues are small, unpaginated lists).
   */
  protected readonly sourceFilter = signal<SourceFilterMode>(readSourceFilter('shifa:packing-source'));

  /** Keeps a row when it matches the active source filter. */
  private matchesSource = (row: PackingQueueRow): boolean =>
    matchesSourceMode(row.source, this.sourceFilter());

  /** Switch the source filter and persist it; reset every queue to the first page. */
  setSourceFilter(mode: SourceFilterMode): void {
    this.sourceFilter.set(mode);
    writeSourceFilter('shifa:packing-source', mode);
    this.packPage.set(0);
    this.handoverPage.set(0);
    this.quikShipPage.set(0);
    this.inHousePage.set(0);
  }

  // --- Work queues (to pack / hand over) + read-only status sections ------
  // These hold the RAW (unfiltered) lists from the server; the source filter is
  // applied in the derived signals below so switching the filter never needs a
  // reload.
  private readonly ordersToPackRaw = signal<PackingQueueRow[]>([]);
  private readonly awaitingHandoverRaw = signal<PackingQueueRow[]>([]);
  private readonly quikShipStatusRaw = signal<PackingQueueRow[]>([]);
  private readonly inHouseDeliveriesRaw = signal<PackingQueueRow[]>([]);

  /** Source-filtered work queues (what the UI actually renders). */
  protected readonly ordersToPack = computed(() => this.ordersToPackRaw().filter(this.matchesSource));
  protected readonly awaitingHandover = computed(() =>
    this.awaitingHandoverRaw().filter(this.matchesSource),
  );
  /** Handed-over COURIER orders — QuikShipX pickup + tracking drives these (read-only). */
  protected readonly quikShipStatus = computed(() =>
    this.quikShipStatusRaw().filter(this.matchesSource),
  );
  /** Handed-over IN-HOUSE orders — the team advances these manually. */
  protected readonly inHouseDeliveries = computed(() =>
    this.inHouseDeliveriesRaw().filter(this.matchesSource),
  );
  protected readonly queueLoading = signal(true);
  /** The order currently running a queue action (pack/handover), for spinners. */
  protected readonly busyOrderId = signal<number | null>(null);
  /** The order whose label is currently being fetched/opened. */
  protected readonly labelBusyId = signal<number | null>(null);
  /** The order whose QuikShip courier label is currently being opened. */
  protected readonly quikLabelBusyId = signal<number | null>(null);

  // --- Client-side paging (per queue, shared page size) -------------------
  /** Zero-based current page for each work queue. */
  protected readonly packPage = signal(0);
  protected readonly handoverPage = signal(0);
  /** Rows per page, shared across the two work queues (persisted per table). */
  protected readonly queueSize = signal(readPageSize('packingQueue', 10));

  /** Slices a full queue list to the current page. */
  private pageSlice(all: PackingQueueRow[], page: number): PackingQueueRow[] {
    const size = this.queueSize();
    const start = page * size;
    return all.slice(start, start + size);
  }

  /** The two work-queue sections rendered as lists, in workflow order. */
  protected readonly queueSections = computed(() => {
    const size = this.queueSize();
    const totalPages = (all: PackingQueueRow[]) => Math.max(1, Math.ceil(all.length / size));
    const pack = this.ordersToPack();
    const handover = this.awaitingHandover();
    return [
      {
        kind: 'pack' as const,
        title: 'Orders to pack',
        short: 'To pack',
        icon: 'ti-box',
        accent: '#1f5d3f',
        accentSoft: '#e4f2ea',
        actionLabel: 'Pack',
        actionIcon: 'ti-checkbox',
        hint: 'Print the label, then mark the order packed. Printing the QuikShip label moves it to Awaiting Handover automatically.',
        orders: this.pageSlice(pack, this.packPage()),
        allOrders: pack,
        page: this.packPage(),
        totalElements: pack.length,
        totalPages: totalPages(pack),
      },
      {
        kind: 'handover' as const,
        title: 'Awaiting handover',
        short: 'Handover',
        icon: 'ti-package',
        accent: '#0284c7',
        accentSoft: '#e0f2fe',
        actionLabel: 'Handover',
        actionIcon: 'ti-truck-loading',
        hint: 'Hand these packed orders to the courier (QuikShip) or your in-house driver.',
        orders: this.pageSlice(handover, this.handoverPage()),
        allOrders: handover,
        page: this.handoverPage(),
        totalElements: handover.length,
        totalPages: totalPages(handover),
      },
    ];
  });

  /** Go to a page within one of the two work queues. */
  goToQueuePage(kind: 'pack' | 'handover', page: number): void {
    switch (kind) {
      case 'pack':
        this.packPage.set(page);
        break;
      case 'handover':
        this.handoverPage.set(page);
        break;
    }
  }

  /** Change the shared rows-per-page for the work queues (resets to first page). */
  setQueueSize(size: number): void {
    this.queueSize.set(size);
    writePageSize('packingQueue', size);
    this.packPage.set(0);
    this.handoverPage.set(0);
  }

  /** After a reload shrinks a queue, avoid being stranded on a now-empty trailing page. */
  private clampQueuePages(): void {
    const size = this.queueSize();
    const clamp = (all: PackingQueueRow[], page: ReturnType<typeof signal<number>>) => {
      const maxPage = Math.max(0, Math.ceil(all.length / size) - 1);
      if (page() > maxPage) {
        page.set(maxPage);
      }
    };
    clamp(this.ordersToPack(), this.packPage);
    clamp(this.awaitingHandover(), this.handoverPage);
  }

  // --- Read-only status sections paging (QuickShip + In-House) ------------
  protected readonly quikShipPage = signal(0);
  protected readonly inHousePage = signal(0);
  protected readonly statusSize = signal(readPageSize('packingStatus', 10));
  protected readonly quikShipTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.quikShipStatus().length / this.statusSize())),
  );
  protected readonly inHouseTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.inHouseDeliveries().length / this.statusSize())),
  );
  protected readonly quikShipPageItems = computed<PackingQueueRow[]>(() => {
    const s = this.quikShipPage() * this.statusSize();
    return this.quikShipStatus().slice(s, s + this.statusSize());
  });
  protected readonly inHousePageItems = computed<PackingQueueRow[]>(() => {
    const s = this.inHousePage() * this.statusSize();
    return this.inHouseDeliveries().slice(s, s + this.statusSize());
  });
  goToQuikShipPage(p: number): void {
    this.quikShipPage.set(p);
  }
  goToInHousePage(p: number): void {
    this.inHousePage.set(p);
  }
  setStatusSize(size: number): void {
    this.statusSize.set(size);
    writePageSize('packingStatus', size);
    this.quikShipPage.set(0);
    this.inHousePage.set(0);
  }

  /** Total orders waiting across both work queues (hero context). */
  protected readonly totalInQueues = computed(
    () => this.ordersToPack().length + this.awaitingHandover().length,
  );

  /** Scroll to a work-queue section when its KPI tile is tapped. */
  scrollToSection(kind: string): void {
    if (typeof document === 'undefined') {
      return;
    }
    document.getElementById('pk-section-' + kind)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  ngOnInit(): void {
    this.loadQueues();
    this.loadQueue();
  }

  /** Loads the orders-to-pack / awaiting-handover queues + the two status sections. */
  loadQueue(): void {
    this.queueLoading.set(true);
    this.service.queue().subscribe({
      next: (q) => {
        this.ordersToPackRaw.set(q.ordersToPack);
        this.awaitingHandoverRaw.set(q.awaitingHandover);
        this.quikShipStatusRaw.set(q.quikShipStatus);
        this.inHouseDeliveriesRaw.set(q.inHouseDeliveries);
        this.clampQueuePages();
        this.queueLoading.set(false);
      },
      error: () => {
        this.queueLoading.set(false);
      },
    });
  }

  // --- QuikShip courier labels on the Orders-to-Pack rows -----------------
  //
  // QuikShip (courier) orders in the "orders to pack" queue carry a
  // QuikShipX-hosted shipping-label PDF (quikShipXLabelUrl). The packer opens it
  // to print; opening it marks it printed and auto-advances the order to PACKED
  // on the backend, so we reload the queue after.

  /** Order ids selected for QuikShipX label printing / marking printed. */
  protected readonly selectedQuikLabel = signal<Set<number>>(new Set<number>());
  /** True while marking selected labels printed. */
  protected readonly markingPrinted = signal(false);

  protected readonly selectedQuikLabelCount = computed(() => this.selectedQuikLabel().size);

  isQuikLabelSelected(id: number): boolean {
    return this.selectedQuikLabel().has(id);
  }

  toggleQuikLabelSelection(id: number): void {
    const next = new Set(this.selectedQuikLabel());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectedQuikLabel.set(next);
  }

  /** Only rows that actually have a QuikShip label are selectable (in-house rows have none). */
  private quikLabelRows(rows: PackingQueueRow[]): PackingQueueRow[] {
    return rows.filter((r) => !!r.quikShipXLabelUrl);
  }

  /** True when every QuikShip-label row on the page is selected. */
  allQuikLabelSelected(rows: PackingQueueRow[]): boolean {
    const selectable = this.quikLabelRows(rows);
    if (selectable.length === 0) {
      return false;
    }
    const sel = this.selectedQuikLabel();
    return selectable.every((r) => sel.has(r.id));
  }

  /** Header "select all" toggle — selects only the rows that have a QuikShip label. */
  toggleSelectAllQuikLabel(rows: PackingQueueRow[]): void {
    const selectable = this.quikLabelRows(rows);
    const next = new Set(this.selectedQuikLabel());
    const allSelected = selectable.length > 0 && selectable.every((r) => next.has(r.id));
    if (allSelected) {
      selectable.forEach((r) => next.delete(r.id));
    } else {
      selectable.forEach((r) => next.add(r.id));
    }
    this.selectedQuikLabel.set(next);
  }

  /**
   * Opens a single order's QuikShipX label in a new tab and marks it printed.
   * Printing the QuikShip label auto-advances the order to Packed on the backend,
   * so the queue is reloaded once the label is marked printed.
   */
  openQuikLabel(row: PackingQueueRow): void {
    if (!row.quikShipXLabelUrl) {
      this.toasts.error('This order has no QuikShip label URL yet.');
      return;
    }
    if (this.quikLabelBusyId() !== null) {
      return;
    }
    window.open(row.quikShipXLabelUrl, '_blank', 'noopener');
    this.quikLabelBusyId.set(row.id);
    this.markPrinted([row.id]);
  }

  /** Builds the Delhivery public tracking URL for an AWB (QuikShipX ships via Delhivery). */
  trackUrl(awb: string | null | undefined): string | null {
    const trimmed = (awb ?? '').trim();
    return trimmed ? `https://www.delhivery.com/track-v2/package/${encodeURIComponent(trimmed)}` : null;
  }

  /** Opens the courier tracking/history page for an AWB in a new tab (stops the row click). */
  openTracking(awb: string | null | undefined, event?: Event): void {
    event?.stopPropagation();
    const url = this.trackUrl(awb);
    if (url) {
      window.open(url, '_blank', 'noopener');
    }
  }

  /**
   * Prints every selected order's QuikShipX label — opens each label PDF in its
   * own tab (QuikShipX serves one PDF per order), then marks them printed. The
   * browser may block multiple pop-ups; the count toast confirms how many opened.
   */
  printSelectedQuikLabels(): void {
    const rows = this.ordersToPack().filter(
      (r) => !!r.quikShipXLabelUrl && this.selectedQuikLabel().has(r.id),
    );
    if (rows.length === 0) {
      this.toasts.error('None of the selected orders have a QuikShip label yet.');
      return;
    }
    for (const row of rows) {
      window.open(row.quikShipXLabelUrl as string, '_blank', 'noopener');
    }
    this.markPrinted(rows.map((r) => r.id));
  }

  /**
   * Marks the given orders' QuikShipX labels printed. Because printing the
   * QuikShip label auto-advances the order to Packed on the backend, the queues
   * are reloaded on completion so the row moves to Awaiting Handover.
   */
  markPrinted(ids: number[]): void {
    if (ids.length === 0 || this.markingPrinted()) {
      this.quikLabelBusyId.set(null);
      return;
    }
    this.markingPrinted.set(true);
    this.service.markLabelsPrinted(ids).subscribe({
      next: (res) => {
        this.markingPrinted.set(false);
        this.quikLabelBusyId.set(null);
        if (res.marked > 0) {
          this.toasts.success(`Marked ${res.marked} label${res.marked === 1 ? '' : 's'} printed`);
        }
        this.selectedQuikLabel.set(new Set<number>());
        this.loadQueue();
      },
      error: () => {
        this.markingPrinted.set(false);
        this.quikLabelBusyId.set(null);
        this.toasts.error('Could not update label status. Please try again.');
        this.loadQueue();
      },
    });
  }

  /** Opens the internal label PDF (barcode + details) for printing. */
  printLabel(order: PackingQueueRow): void {
    if (this.labelBusyId() !== null) {
      return;
    }
    this.labelBusyId.set(order.id);
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
        this.labelBusyId.set(null);
      },
      error: () => {
        this.toasts.error('Could not open the label. Please try again.');
        this.labelBusyId.set(null);
      },
    });
  }

  // --- Multi-pack: set number of boxes (product-audit §4.2) ---------------

  /**
   * Set how many boxes an order ships in, then the label print produces one
   * copy per box. Ignores invalid input and clamps to 1–50.
   */
  setBoxes(order: PackingQueueRow, raw: string): void {
    const count = Math.max(1, Math.min(50, Math.floor(Number(raw) || 1)));
    this.service.setPackages(order.id, count).subscribe({
      next: () => this.toasts.success(`${order.orderCode}: ${count} box${count === 1 ? '' : 'es'} — print to get ${count} label${count === 1 ? '' : 's'}`),
      error: () => this.toasts.error('Could not update the box count. Please try again.'),
    });
  }

  // --- Multi-label print (product-audit §4.1) -----------------------------

  /** Order ids selected for batch label printing (from the "to pack" queue). */
  protected readonly selectedForLabel = signal<Set<number>>(new Set<number>());
  /** True while the combined bulk-label PDF is being generated. */
  protected readonly bulkLabelBusy = signal(false);

  /** How many orders are currently selected for batch printing. */
  protected readonly selectedLabelCount = computed(() => this.selectedForLabel().size);

  isSelectedForLabel(id: number): boolean {
    return this.selectedForLabel().has(id);
  }

  /** True when every order in the given queue is selected for label printing. */
  allSelectedForLabel(rows: PackingQueueRow[]): boolean {
    if (rows.length === 0) {
      return false;
    }
    const sel = this.selectedForLabel();
    return rows.every((o) => sel.has(o.id));
  }

  /**
   * Header "select all" toggle for the pack queue: if every row is already
   * selected, clear them; otherwise select them all (for batch label printing).
   */
  toggleSelectAllForLabel(rows: PackingQueueRow[]): void {
    const next = new Set(this.selectedForLabel());
    const allSelected = rows.length > 0 && rows.every((o) => next.has(o.id));
    if (allSelected) {
      rows.forEach((o) => next.delete(o.id));
    } else {
      rows.forEach((o) => next.add(o.id));
    }
    this.selectedForLabel.set(next);
  }

  /** Toggle an order's selection for batch label printing. */
  toggleLabelSelection(id: number): void {
    const next = new Set(this.selectedForLabel());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectedForLabel.set(next);
  }

  /** Print one combined PDF with a label for every selected order (Req 10.4, §4.1). */
  printSelectedLabels(): void {
    const ids = Array.from(this.selectedForLabel());
    if (ids.length === 0 || this.bulkLabelBusy()) {
      return;
    }
    this.bulkLabelBusy.set(true);
    this.service.bulkLabels(ids).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = `labels-${ids.length}-orders.pdf`;
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.bulkLabelBusy.set(false);
        this.selectedForLabel.set(new Set<number>());
      },
      error: () => {
        this.toasts.error('Could not print the selected labels. Please try again.');
        this.bulkLabelBusy.set(false);
      },
    });
  }

  // --- Bulk handover (PACKED "awaiting handover" queue) -------------------

  /** Order ids selected for a bulk hand-over from the "awaiting handover" queue. */
  protected readonly selectedForHandover = signal<Set<number>>(new Set<number>());
  /** True while the bulk hand-over is running. */
  protected readonly bulkHandoverBusy = signal(false);

  /** How many orders are currently selected for bulk hand-over. */
  protected readonly selectedHandoverCount = computed(() => this.selectedForHandover().size);

  isSelectedForHandover(id: number): boolean {
    return this.selectedForHandover().has(id);
  }

  /** True when every order in the handover queue is selected. */
  allSelectedForHandover(rows: PackingQueueRow[]): boolean {
    if (rows.length === 0) {
      return false;
    }
    const sel = this.selectedForHandover();
    return rows.every((o) => sel.has(o.id));
  }

  /** Header "select all" toggle for the awaiting-handover queue. */
  toggleSelectAllForHandover(rows: PackingQueueRow[]): void {
    const next = new Set(this.selectedForHandover());
    const allSelected = rows.length > 0 && rows.every((o) => next.has(o.id));
    if (allSelected) {
      rows.forEach((o) => next.delete(o.id));
    } else {
      rows.forEach((o) => next.add(o.id));
    }
    this.selectedForHandover.set(next);
  }

  /** Toggle an order's selection for bulk hand-over. */
  toggleHandoverSelection(id: number): void {
    const next = new Set(this.selectedForHandover());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectedForHandover.set(next);
  }

  /**
   * Opens the "handed to" popup once for the whole selection; on confirm the
   * same name/phone are applied to every selected order via the existing
   * per-order handover endpoint (partial failures are reported, not fatal).
   */
  handoverSelected(): void {
    const ids = Array.from(this.selectedForHandover());
    if (ids.length === 0 || this.bulkHandoverBusy()) {
      return;
    }
    this.handoverPrompt.set({
      orderCode: `${ids.length} order${ids.length === 1 ? '' : 's'}`,
      run: (name, phone, vehicle) => this.runBulkHandover(name, phone, vehicle),
    });
  }

  private runBulkHandover(name: string, phone: string, vehicle: string): void {
    const ids = Array.from(this.selectedForHandover());
    if (ids.length === 0 || this.bulkHandoverBusy()) {
      return;
    }
    this.bulkHandoverBusy.set(true);
    const calls = ids.map((id) =>
      this.service.handover(id, name, phone, vehicle).pipe(
        map(() => ({ id, ok: true })),
        catchError(() => of({ id, ok: false })),
      ),
    );
    forkJoin(calls).subscribe((results) => {
      const okCount = results.filter((r) => r.ok).length;
      const failCount = results.length - okCount;
      this.bulkHandoverBusy.set(false);
      this.selectedForHandover.set(new Set<number>());
      if (failCount === 0) {
        this.toasts.success(`Handed over ${okCount} order${okCount === 1 ? '' : 's'} to delivery`);
      } else {
        this.toasts.error(`Handed over ${okCount}, ${failCount} failed (status may have changed).`);
      }
      this.loadQueue();
      this.loadQueues();
    });
  }

  // --- Bulk in-house dispatch status update (HANDED_TO_DELIVERY queue) -----
  //
  // Dispatch is manual only for IN-HOUSE orders — a courier-partner order is
  // tracked by the partner, so it is not selectable here. The packer multi-selects
  // in-house orders and sets a delivery status (Out for delivery / Delivered / …),
  // mirroring the order-detail "Update status" dropdown.

  /** Order ids selected for the bulk in-house status update. */
  protected readonly selectedForDispatch = signal<Set<number>>(new Set<number>());
  /** True while the bulk update is running. */
  protected readonly bulkDispatchBusy = signal(false);
  /** The status to apply to the selection (empty until the packer picks one). */
  protected readonly dispatchStatus = signal<string>('');

  /**
   * The statuses a packer can set from the dispatch queue. Every row here is
   * {@code HANDED_TO_DELIVERY}, so the legal next in-house stages are Dispatched /
   * In transit / Out for delivery / Delivered (mirrors the order-detail dropdown;
   * the backend still re-checks legality + the in-house rule per order).
   */
  protected readonly dispatchStatusOptions = MANUAL_DELIVERY_STAGE_OPTIONS.filter((o) =>
    ['DISPATCHED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED'].includes(o.value),
  );

  /** How many orders are currently selected. */
  protected readonly selectedDispatchCount = computed(() => this.selectedForDispatch().size);

  /** Whether a row is an in-house order (only these can be manually dispatched). */
  isInHouseRow(row: PackingQueueRow): boolean {
    return row.deliveryMethod === 'IN_HOUSE';
  }

  /** The in-house subset of a dispatch queue (the only selectable rows). */
  private inHouseRows(rows: PackingQueueRow[]): PackingQueueRow[] {
    return rows.filter((o) => this.isInHouseRow(o));
  }

  isSelectedForDispatch(id: number): boolean {
    return this.selectedForDispatch().has(id);
  }

  /** True when every IN-HOUSE order in the dispatch queue is selected. */
  allSelectedForDispatch(rows: PackingQueueRow[]): boolean {
    const selectable = this.inHouseRows(rows);
    if (selectable.length === 0) {
      return false;
    }
    const sel = this.selectedForDispatch();
    return selectable.every((o) => sel.has(o.id));
  }

  /** Header "select all" toggle — selects only the IN-HOUSE rows. */
  toggleSelectAllForDispatch(rows: PackingQueueRow[]): void {
    const selectable = this.inHouseRows(rows);
    const next = new Set(this.selectedForDispatch());
    const allSelected = selectable.length > 0 && selectable.every((o) => next.has(o.id));
    if (allSelected) {
      selectable.forEach((o) => next.delete(o.id));
    } else {
      selectable.forEach((o) => next.add(o.id));
    }
    this.selectedForDispatch.set(next);
  }

  /** Toggle an in-house order's selection (courier rows are not selectable). */
  toggleDispatchSelection(row: PackingQueueRow): void {
    if (!this.isInHouseRow(row)) {
      return;
    }
    const next = new Set(this.selectedForDispatch());
    if (next.has(row.id)) {
      next.delete(row.id);
    } else {
      next.add(row.id);
    }
    this.selectedForDispatch.set(next);
  }

  /** Sets the status the bulk action will apply. */
  setDispatchStatus(status: string): void {
    this.dispatchStatus.set(status);
  }

  /**
   * Applies the chosen delivery status to every selected IN-HOUSE order in one
   * call. Courier orders / illegal moves come back skipped with a reason (partial
   * success); reuses the order-detail per-order rules incl. settlement on Delivered.
   */
  dispatchSelected(): void {
    const ids = Array.from(this.selectedForDispatch());
    const status = this.dispatchStatus();
    if (ids.length === 0 || !status || this.bulkDispatchBusy()) {
      return;
    }
    this.bulkDispatchBusy.set(true);
    this.service.bulkDeliveryStatus(ids, status).subscribe({
      next: (result) => {
        const ok = result.succeeded.length;
        const failed = result.skipped.length;
        this.bulkDispatchBusy.set(false);
        this.selectedForDispatch.set(new Set<number>());
        const label = this.dispatchStatusOptions.find((o) => o.value === status)?.label ?? status;
        if (failed === 0) {
          this.toasts.success(`Set ${ok} order${ok === 1 ? '' : 's'} to ${label}`);
        } else if (ok === 0) {
          this.toasts.error(
            `None updated — ${failed} skipped (${result.skipped[0]?.reason ?? 'not eligible'}).`,
          );
        } else {
          this.toasts.error(`Set ${ok} to ${label}, ${failed} skipped (see order status).`);
        }
        this.loadQueue();
        this.loadQueues();
      },
      error: (err: HttpErrorResponse) => {
        this.bulkDispatchBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not update the selected orders.');
      },
    });
  }

  /** Extracts a human message from an error response, if any. */
  private messageOf(err: HttpErrorResponse): string | null {
    const body = err?.error as { message?: string } | undefined;
    return body?.message ?? null;
  }

  /** Opens the order detail (via the Orders page filtered to this order code). */
  openOrder(order: PackingQueueRow): void {
    void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
  }

  /** Runs the primary action for a work-queue row based on its section kind. */
  queueAction(kind: 'pack' | 'handover', order: PackingQueueRow): void {
    switch (kind) {
      case 'pack':
        this.markPacked(order);
        break;
      case 'handover':
        this.handoverOrder(order);
        break;
    }
  }

  /** Mark an order packed straight from the queue (equivalent to scanning it). */
  markPacked(order: PackingQueueRow): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.busyOrderId.set(order.id);
    this.service.scan(order.orderCode).subscribe({
      next: (res) => {
        this.busyOrderId.set(null);
        this.onSuccess(order.orderCode, res);
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) => {
        this.busyOrderId.set(null);
        this.onError(order.orderCode, err);
        this.loadQueue();
      },
    });
  }

  /**
   * The pending handover awaiting the "handed to" popup (product-audit §4.3).
   * Holds the order code (for the popup title) and a callback that performs the
   * actual handover with the entered name/phone, so both the queue and the
   * inline-scan flows share one popup.
   */
  protected readonly handoverPrompt = signal<{
    orderCode: string;
    run: (name: string, phone: string, vehicle: string) => void;
  } | null>(null);

  /** A scanned order awaiting the packer's explicit confirmation. */
  protected readonly pendingPreview = signal<{
    message: string;
    order: PackingScanResponse['order'];
    nextAction: 'PACK' | 'HANDOVER' | 'DISPATCH' | 'NONE';
    nextStatus: string | null;
    /** The order/packaging note (null when none) — shown to the packer on scan. */
    notes?: string | null;
  } | null>(null);

  /** Opens the "handed to" popup for an order; the callback runs on confirm. */
  private openHandoverPrompt(
    orderCode: string,
    run: (name: string, phone: string, vehicle: string) => void,
  ): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.handoverPrompt.set({ orderCode, run });
  }

  /**
   * Confirms the popup with the entered name/phone plus the optional vehicle /
   * transport reference (in-house deliveries have no AWB) and runs the handover.
   */
  confirmHandover(name: string, phone: string, vehicle = ''): void {
    const pending = this.handoverPrompt();
    this.handoverPrompt.set(null);
    if (pending) {
      pending.run(name, phone, vehicle);
    }
  }

  /** Closes the handover popup without acting. */
  cancelHandover(): void {
    this.handoverPrompt.set(null);
  }

  /** Hand a packed order over to the courier, straight from the queue. */
  handoverOrder(order: PackingQueueRow): void {
    this.openHandoverPrompt(order.orderCode, (name, phone, vehicle) =>
      this.runQueueHandover(order, name, phone, vehicle),
    );
  }

  private runQueueHandover(
    order: PackingQueueRow,
    name: string,
    phone: string,
    vehicle: string,
  ): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.busyOrderId.set(order.id);
    this.service.handover(order.id, name, phone, vehicle).subscribe({
      next: () => {
        this.busyOrderId.set(null);
        this.toasts.success(`Order ${order.orderCode} handed over to delivery`);
        this.loadQueue();
        this.loadQueues();
      },
      error: (err: HttpErrorResponse) => {
        this.busyOrderId.set(null);
        this.toasts.error((err.error as ApiError | undefined)?.message ?? 'Handover failed.');
        this.loadQueue();
      },
    });
  }

  ngAfterViewInit(): void {
    this.focusInput();
  }

  /** Loads the awaiting-handover / awaiting-dispatch queue counts (Req 9.4, 10.1). */
  loadQueues(): void {
    this.dashboard.roleSummary().subscribe({
      next: (s) => {
        if (s.packing) {
          this.queueSummary.set([
            { label: 'Awaiting packing', value: s.packing.approvedAwaitingPacking, icon: 'ti-box' },
            { label: 'Packed today', value: s.packing.packedToday, icon: 'ti-circle-check' },
            { label: 'Awaiting handover', value: s.packing.awaitingHandover, icon: 'ti-package' },
            { label: 'Awaiting dispatch', value: s.packing.awaitingDispatch, icon: 'ti-truck-delivery' },
          ]);
        } else if (s.admin) {
          this.queueSummary.set([
            { label: 'Awaiting handover', value: s.admin.packedAwaitingHandover, icon: 'ti-package' },
            { label: 'Awaiting dispatch', value: s.admin.handedOverAwaitingDispatch, icon: 'ti-truck-delivery' },
          ]);
        } else {
          this.queueSummary.set([]);
        }
      },
      error: () => {
        /* Non-fatal: the scan flow still works without the queue context. */
      },
    });
  }

  // --- Handover / Dispatch (Req 9.2–9.4, 10.1) ----------------------------

  /** Normalises a raw status string to a workflow phase, format-agnostic. */
  private phaseOf(status: string | undefined): WorkPhase {
    switch ((status ?? '').toUpperCase()) {
      case 'PACKED':
        return 'packed';
      case 'HANDED_TO_DELIVERY':
        return 'handed_over';
      default:
        return 'other';
    }
  }

  /** Hand a packed order over to the delivery courier (PACKED → HANDED_TO_DELIVERY). */
  handover(item: PackWorkItem): void {
    this.openHandoverPrompt(item.orderCode, (name, phone, vehicle) =>
      this.runItemHandover(item, name, phone, vehicle),
    );
  }

  private runItemHandover(item: PackWorkItem, name: string, phone: string, vehicle: string): void {
    if (item.busy) {
      return;
    }
    this.patchItem(item.id, { busy: true, error: null });
    this.service.handover(item.id, name, phone, vehicle).subscribe({
      next: (order) => {
        this.patchItem(item.id, {
          busy: false,
          status: order.orderStatus,
          phase: this.phaseOf(order.orderStatus),
        });
        this.toasts.success(`Order ${item.orderCode} handed over to delivery`);
        this.loadQueues();
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) => this.onActionError(item.id, err, 'Handover'),
    });
  }

  /** Dispatch a handed-over order — enqueues courier assignment (Req 10.1). */
  dispatch(item: PackWorkItem): void {
    if (item.busy) {
      return;
    }
    this.patchItem(item.id, { busy: true, error: null });
    this.service.dispatch(item.id).subscribe({
      next: () => {
        // Dispatch enqueues courier assignment; the order stays HANDED_TO_DELIVERY
        // until the async assignment advances it, so we mark the local phase done.
        this.patchItem(item.id, { busy: false, phase: 'dispatched' });
        this.toasts.success(`Order ${item.orderCode} dispatched for courier assignment`);
        this.loadQueues();
        this.loadQueue();
      },
      error: (err: HttpErrorResponse) => this.onActionError(item.id, err, 'Dispatch'),
    });
  }

  private onActionError(id: number, err: HttpErrorResponse, action: string): void {
    const apiError = err.error as ApiError | undefined;
    const message = apiError?.message ?? `${action} failed. Please try again.`;
    this.patchItem(id, { busy: false, error: message });
    this.toasts.error(message);
    this.loadQueues();
  }

  /** Immutably patches a tracked work item by id. */
  private patchItem(id: number, patch: Partial<PackWorkItem>): void {
    this.workItems.update((items) =>
      items.map((it) => (it.id === id ? { ...it, ...patch } : it)),
    );
  }

  /** Removes a completed/tracked work item from the session list. */
  dismissItem(id: number): void {
    this.workItems.update((items) => items.filter((it) => it.id !== id));
  }

  /** Resolves a typed or handheld-scanned barcode before any status mutation. */
  submit(): void {
    if (this.submitting() || this.pendingPreview()) {
      return;
    }
    const barcode = this.form.getRawValue().barcode.trim();
    if (!barcode) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.service.preview(barcode).subscribe({
      next: (preview) => {
        this.submitting.set(false);
        this.pendingPreview.set(preview);
      },
      error: (err: HttpErrorResponse) => this.onError(barcode, err),
    });
  }

  /** Cancels a scan preview without changing the order. */
  cancelPreview(): void {
    this.pendingPreview.set(null);
    this.finish();
  }

  /** Confirms the server-proposed next packing operation. */
  confirmPreview(): void {
    const preview = this.pendingPreview();
    if (!preview || preview.nextAction === 'NONE' || this.submitting()) {
      return;
    }

    switch (preview.nextAction) {
      case 'PACK':
        this.commitPack(preview);
        break;
      case 'HANDOVER':
        this.pendingPreview.set(null);
        this.openHandoverPrompt(preview.order.orderCode, (name, phone, vehicle) =>
          this.commitHandover(preview, name, phone, vehicle),
        );
        break;
      case 'DISPATCH':
        this.commitDispatch(preview);
        break;
    }
  }

  previewActionLabel(action: 'PACK' | 'HANDOVER' | 'DISPATCH' | 'NONE'): string {
    switch (action) {
      case 'PACK':
        return 'Mark packed';
      case 'HANDOVER':
        return 'Handover to delivery';
      case 'DISPATCH':
        return 'Dispatch for courier assignment';
      default:
        return 'No packing move available';
    }
  }

  private commitPack(preview: NonNullable<ReturnType<typeof this.pendingPreview>>): void {
    this.pendingPreview.set(null);
    this.submitting.set(true);
    this.service.scan(preview.order.orderCode).subscribe({
      next: (response) => this.onSuccess(preview.order.orderCode, response),
      error: (err: HttpErrorResponse) => this.onError(preview.order.orderCode, err),
    });
  }

  private commitHandover(
    preview: NonNullable<ReturnType<typeof this.pendingPreview>>,
    name: string,
    phone: string,
    vehicle: string,
  ): void {
    this.submitting.set(true);
    this.service.handover(preview.order.id, name, phone, vehicle).subscribe({
      next: (order) => this.onPreviewMoveSuccess(
        preview,
        `Order ${preview.order.orderCode} handed over to delivery`,
        String(order.orderStatus),
        this.phaseOf(String(order.orderStatus)),
      ),
      error: (err: HttpErrorResponse) => this.onError(preview.order.orderCode, err),
    });
  }

  private commitDispatch(preview: NonNullable<ReturnType<typeof this.pendingPreview>>): void {
    this.pendingPreview.set(null);
    this.submitting.set(true);
    this.service.dispatch(preview.order.id).subscribe({
      next: () => this.onPreviewMoveSuccess(
        preview,
        `Order ${preview.order.orderCode} dispatched for courier assignment`,
        String(preview.order.orderStatus),
        'dispatched',
      ),
      error: (err: HttpErrorResponse) => this.onError(preview.order.orderCode, err),
    });
  }

  private onPreviewMoveSuccess(
    preview: NonNullable<ReturnType<typeof this.pendingPreview>>,
    message: string,
    status: string,
    phase: WorkPhase,
  ): void {
    this.banner.set({
      outcome: 'moved',
      title: message,
      detail: `${preview.order.customerName} · ${preview.order.customerMobile}`,
    });
    this.appendLog({
      barcode: preview.order.orderCode,
      outcome: 'moved',
      message,
      currentStatus: status,
      at: new Date(),
    });
    this.trackWorkItem({
      id: preview.order.id,
      orderCode: preview.order.orderCode,
      customerName: preview.order.customerName,
      status,
      phase,
      busy: false,
      error: null,
    });
    this.loadQueues();
    this.loadQueue();
    this.finish();
  }

  /** Clear the running scan log. */
  clearLog(): void {
    this.log.set([]);
  }

  // --- Camera scan (FEATURE-ROADMAP §8.2) ---------------------------------

  /** Opens the phone-camera barcode scanner overlay. */
  openCamera(): void {
    if (!this.submitting() && !this.pendingPreview()) {
      this.cameraOpen.set(true);
    }
  }

  /** Closes the camera scanner overlay. */
  closeCamera(): void {
    this.cameraOpen.set(false);
  }

  /** A barcode decoded from the camera follows the same preview/confirm flow. */
  onCameraScanned(code: string): void {
    this.cameraOpen.set(false);
    this.form.controls.barcode.setValue(code.trim());
    this.submit();
  }

  formatStatus(status: string | undefined | null): string {
    return status ? status.replaceAll('_', ' ') : '';
  }

  // --- Outcome handling ---------------------------------------------------

  private onSuccess(barcode: string, response: PackingScanResponse): void {
    const code = response.order?.orderCode ?? barcode;
    this.banner.set({
      outcome: 'packed',
      title: `Order ${code} marked Packed`,
      detail: response.order
        ? `${response.order.customerName} · ${response.order.customerMobile}`
        : response.message,
    });
    this.appendLog({
      barcode,
      outcome: 'packed',
      message: response.message,
      at: new Date(),
    });
    if (response.order) {
      this.trackWorkItem({
        id: response.order.id,
        orderCode: response.order.orderCode,
        customerName: response.order.customerName,
        status: String(response.order.orderStatus),
        phase: this.phaseOf(String(response.order.orderStatus)),
        busy: false,
        error: null,
      });
      this.loadQueues();
      this.loadQueue();
    }
    this.finish();
  }

  /** Adds (or refreshes) a tracked work item, newest first, de-duped by id. */
  private trackWorkItem(item: PackWorkItem): void {
    this.workItems.update((items) => [item, ...items.filter((it) => it.id !== item.id)].slice(0, 20));
  }

  private onError(barcode: string, err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    const code = apiError?.code;

    if (code === 'BARCODE_NOT_RECOGNIZED' || err.status === 404) {
      this.banner.set({
        outcome: 'not-recognized',
        title: 'Barcode not recognized',
        detail: `No order matches "${barcode}". Check the label and try again.`,
      });
      this.appendLog({
        barcode,
        outcome: 'not-recognized',
        message: apiError?.message ?? 'Barcode not recognized.',
        at: new Date(),
      });
    } else if (code === 'ORDER_NOT_PACKABLE' || err.status === 409) {
      const currentStatus = this.extractCurrentStatus(apiError);
      const pretty = this.formatStatus(currentStatus);
      this.banner.set({
        outcome: 'wrong-status',
        title: 'Order changed before confirmation',
        detail: pretty
          ? `Order is now ${pretty}. Scan again to see its current next move.`
          : (apiError?.message ?? 'This order can no longer be moved as previewed.'),
      });
      this.appendLog({
        barcode,
        outcome: 'wrong-status',
        message: apiError?.message ?? 'Order status changed before confirmation.',
        currentStatus,
        at: new Date(),
      });
    } else {
      this.banner.set({
        outcome: 'error',
        title: 'Scan & Move failed',
        detail: apiError?.message ?? 'Something went wrong. Please try again.',
      });
      this.appendLog({
        barcode,
        outcome: 'error',
        message: apiError?.message ?? 'Scan & Move failed.',
        at: new Date(),
      });
    }
    this.pendingPreview.set(null);
    this.finish();
  }

  /** Pulls "currentStatus: X" out of the error details returned by workflow endpoints. */
  private extractCurrentStatus(apiError: ApiError | undefined): string | undefined {
    const detail = apiError?.details?.find((d) => d.startsWith('currentStatus:'));
    return detail?.split(':')[1]?.trim();
  }

  private appendLog(entry: ScanLogEntry): void {
    this.log.update((entries) => [entry, ...entries].slice(0, 50));
  }

  private finish(): void {
    this.submitting.set(false);
    this.form.reset({ barcode: '' });
    this.focusInput();
  }

  private focusInput(): void {
    setTimeout(() => this.barcodeInput?.nativeElement.focus({ preventScroll: true }), 0);
  }
}
