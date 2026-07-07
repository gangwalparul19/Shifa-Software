import { DatePipe } from '@angular/common';
import { AfterViewInit, Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from 'core';
import { DashboardService } from '../dashboard/dashboard.service';
import { PackingService } from './packing.service';
import { PackingScanResponse, ScanLogEntry, ScanOutcome } from './packing.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { ToastService } from '../shared/toast.service';

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
  imports: [ReactiveFormsModule, DatePipe, PageHeaderComponent, StatusBadgeComponent],
  templateUrl: './scan.component.html',
  styleUrl: './scan.component.css',
})
export class ScanComponent implements OnInit, AfterViewInit {
  private readonly service = inject(PackingService);
  private readonly dashboard = inject(DashboardService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  @ViewChild('barcodeInput') private barcodeInput?: ElementRef<HTMLInputElement>;

  protected readonly form = this.fb.nonNullable.group({
    barcode: ['', [Validators.required]],
  });

  protected readonly submitting = signal(false);
  protected readonly banner = signal<ScanBanner | null>(null);
  protected readonly log = signal<ScanLogEntry[]>([]);

  /** Orders the packer is actively moving through handover / dispatch this session. */
  protected readonly workItems = signal<PackWorkItem[]>([]);

  // --- Awaiting-handover / awaiting-dispatch context (Req 9.4, 10.1) ------
  private readonly queueSummary = signal<QueueCard[]>([]);

  /** The awaiting-* queue cards surfaced above the scan area. */
  protected readonly queues = computed<QueueCard[]>(() => this.queueSummary());

  ngOnInit(): void {
    this.loadQueues();
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
    if (item.busy) {
      return;
    }
    this.patchItem(item.id, { busy: true, error: null });
    this.service.handover(item.id).subscribe({
      next: (order) => {
        this.patchItem(item.id, {
          busy: false,
          status: order.orderStatus,
          phase: this.phaseOf(order.orderStatus),
        });
        this.toasts.success(`Order ${item.orderCode} handed over to delivery`);
        this.loadQueues();
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

  /** Submit the scanned/typed barcode (Enter or the Scan button). */
  submit(): void {
    if (this.submitting()) {
      return;
    }
    const barcode = this.form.getRawValue().barcode.trim();
    if (!barcode) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.service.scan(barcode).subscribe({
      next: (response) => this.onSuccess(barcode, response),
      error: (err: HttpErrorResponse) => this.onError(barcode, err),
    });
  }

  /** Clear the running scan log. */
  clearLog(): void {
    this.log.set([]);
  }

  formatStatus(status: string | undefined): string {
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
    // Surface the just-packed order for an inline Handover action (Req 9.2–9.4).
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
        title: 'Cannot pack this order',
        detail: pretty
          ? `Order is ${pretty} — only labelled orders can be packed.`
          : (apiError?.message ?? 'This order cannot be packed.'),
      });
      this.appendLog({
        barcode,
        outcome: 'wrong-status',
        message: apiError?.message ?? 'Order cannot be packed.',
        currentStatus,
        at: new Date(),
      });
    } else {
      this.banner.set({
        outcome: 'error',
        title: 'Scan failed',
        detail: apiError?.message ?? 'Something went wrong. Please try again.',
      });
      this.appendLog({
        barcode,
        outcome: 'error',
        message: apiError?.message ?? 'Scan failed.',
        at: new Date(),
      });
    }
    this.finish();
  }

  /** Pulls "currentStatus: X" out of the error details (Req 11.4). */
  private extractCurrentStatus(apiError: ApiError | undefined): string | undefined {
    const detail = apiError?.details?.find((d) => d.startsWith('currentStatus:'));
    return detail?.split(':')[1]?.trim();
  }

  private appendLog(entry: ScanLogEntry): void {
    // Newest first, cap the running list to a sensible length.
    this.log.update((entries) => [entry, ...entries].slice(0, 50));
  }

  private finish(): void {
    this.submitting.set(false);
    this.form.reset({ barcode: '' });
    this.focusInput();
  }

  private focusInput(): void {
    // Defer so the DOM has settled before focusing the field.
    setTimeout(() => this.barcodeInput?.nativeElement.focus(), 0);
  }
}
