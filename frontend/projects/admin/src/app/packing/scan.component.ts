import { DatePipe } from '@angular/common';
import { AfterViewInit, Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ApiError } from 'core';
import { DashboardService } from '../dashboard/dashboard.service';
import { PackingService } from './packing.service';
import { PackingQueueRow, PackingScanResponse, ScanLogEntry, ScanOutcome } from './packing.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { ToastService } from '../shared/toast.service';
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
    DatePipe,
    PageHeaderComponent,
    StatusBadgeComponent,
    CameraScannerComponent,
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

  /** Whether the phone-camera scanner overlay is open (FEATURE-ROADMAP §8.2). */
  protected readonly cameraOpen = signal(false);
  protected readonly log = signal<ScanLogEntry[]>([]);

  /** Orders the packer is actively moving through handover / dispatch this session. */
  protected readonly workItems = signal<PackWorkItem[]>([]);

  // --- Awaiting-handover / awaiting-dispatch context (Req 9.4, 10.1) ------
  private readonly queueSummary = signal<QueueCard[]>([]);

  /** The awaiting-* queue cards surfaced above the scan area. */
  protected readonly queues = computed<QueueCard[]>(() => this.queueSummary());

  // --- Work queue lists (orders to pack / hand over / dispatch) -----------
  protected readonly awaitingPacking = signal<PackingQueueRow[]>([]);
  protected readonly awaitingHandover = signal<PackingQueueRow[]>([]);
  protected readonly awaitingDispatch = signal<PackingQueueRow[]>([]);
  protected readonly queueLoading = signal(true);
  /** The order currently running a queue action (pack/handover/dispatch), for spinners. */
  protected readonly busyOrderId = signal<number | null>(null);
  /** The order whose label is currently being fetched/opened. */
  protected readonly labelBusyId = signal<number | null>(null);

  /** The three work-queue sections rendered as lists, in workflow order. */
  protected readonly queueSections = computed(() => [
    {
      kind: 'pack' as const,
      title: 'Orders to pack',
      short: 'To pack',
      icon: 'ti-box',
      accent: '#1f5d3f',
      accentSoft: '#e4f2ea',
      actionLabel: 'Pack',
      actionIcon: 'ti-checkbox',
      hint: 'Print the label, then mark the order packed.',
      orders: this.awaitingPacking(),
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
      hint: 'Hand these packed orders to the delivery courier.',
      orders: this.awaitingHandover(),
    },
    {
      kind: 'dispatch' as const,
      title: 'Awaiting dispatch',
      short: 'Dispatch',
      icon: 'ti-truck-delivery',
      accent: '#b7791f',
      accentSoft: '#fdf0d5',
      actionLabel: 'Dispatch',
      actionIcon: 'ti-truck-delivery',
      hint: 'Dispatch to enqueue courier assignment.',
      orders: this.awaitingDispatch(),
    },
  ]);

  /** Total orders waiting across all three work queues (hero context). */
  protected readonly totalInQueues = computed(
    () => this.awaitingPacking().length + this.awaitingHandover().length + this.awaitingDispatch().length,
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

  /** Loads the awaiting-packing / handover / dispatch work-queue lists. */
  loadQueue(): void {
    this.queueLoading.set(true);
    this.service.queue().subscribe({
      next: (q) => {
        this.awaitingPacking.set(q.awaitingPacking);
        this.awaitingHandover.set(q.awaitingHandover);
        this.awaitingDispatch.set(q.awaitingDispatch);
        this.queueLoading.set(false);
      },
      error: () => {
        this.queueLoading.set(false);
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

  /** Opens the order detail (via the Orders page filtered to this order code). */
  openOrder(order: PackingQueueRow): void {
    void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
  }

  /** Runs the primary action for a work-queue row based on its section kind. */
  queueAction(kind: 'pack' | 'handover' | 'dispatch', order: PackingQueueRow): void {
    switch (kind) {
      case 'pack':
        this.markPacked(order);
        break;
      case 'handover':
        this.handoverOrder(order);
        break;
      case 'dispatch':
        this.dispatchOrder(order);
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
    run: (name: string, phone: string) => void;
  } | null>(null);

  /** A scanned order awaiting the packer's explicit confirmation. */
  protected readonly pendingPreview = signal<{
    message: string;
    order: PackingScanResponse['order'];
    nextAction: 'PACK' | 'HANDOVER' | 'DISPATCH' | 'NONE';
    nextStatus: string | null;
  } | null>(null);

  /** Opens the "handed to" popup for an order; the callback runs on confirm. */
  private openHandoverPrompt(orderCode: string, run: (name: string, phone: string) => void): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.handoverPrompt.set({ orderCode, run });
  }

  /** Confirms the popup with the entered name/phone and runs the handover. */
  confirmHandover(name: string, phone: string): void {
    const pending = this.handoverPrompt();
    this.handoverPrompt.set(null);
    if (pending) {
      pending.run(name, phone);
    }
  }

  /** Closes the handover popup without acting. */
  cancelHandover(): void {
    this.handoverPrompt.set(null);
  }

  /** Hand a packed order over to the courier, straight from the queue. */
  handoverOrder(order: PackingQueueRow): void {
    this.openHandoverPrompt(order.orderCode, (name, phone) => this.runQueueHandover(order, name, phone));
  }

  private runQueueHandover(order: PackingQueueRow, name: string, phone: string): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.busyOrderId.set(order.id);
    this.service.handover(order.id, name, phone).subscribe({
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

  /** Dispatch a handed-over order (enqueues courier assignment), from the queue. */
  dispatchOrder(order: PackingQueueRow): void {
    if (this.busyOrderId() !== null) {
      return;
    }
    this.busyOrderId.set(order.id);
    this.service.dispatch(order.id).subscribe({
      next: () => {
        this.busyOrderId.set(null);
        this.toasts.success(`Order ${order.orderCode} dispatched for courier assignment`);
        this.loadQueue();
        this.loadQueues();
      },
      error: (err: HttpErrorResponse) => {
        this.busyOrderId.set(null);
        this.toasts.error((err.error as ApiError | undefined)?.message ?? 'Dispatch failed.');
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
    this.openHandoverPrompt(item.orderCode, (name, phone) => this.runItemHandover(item, name, phone));
  }

  private runItemHandover(item: PackWorkItem, name: string, phone: string): void {
    if (item.busy) {
      return;
    }
    this.patchItem(item.id, { busy: true, error: null });
    this.service.handover(item.id, name, phone).subscribe({
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
        this.openHandoverPrompt(preview.order.orderCode, (name, phone) =>
          this.commitHandover(preview, name, phone),
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
  ): void {
    this.submitting.set(true);
    this.service.handover(preview.order.id, name, phone).subscribe({
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
