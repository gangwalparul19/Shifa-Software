import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Money } from 'core';
import { ApprovalService } from './approval.service';
import { ApprovalQueueItem } from './approval.model';
import { AdminEventsService } from '../dashboard/admin-events.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { RowActionsMenuComponent, RowAction } from '../shared/row-actions-menu.component';
import { ConfirmService } from '../shared/confirm.service';

interface Toast {
  kind: 'ok' | 'error';
  text: string;
}

/**
 * Admin approval queue view (Req 9.1-9.4).
 *
 * <p>Lists pending-approval orders with a summary row each; a row can be
 * expanded into a detail drawer showing the full line items, applied rates,
 * amounts, payment status, and the payment screenshot (fetched with the auth
 * token and rendered inline; "no screenshot" is handled gracefully). Approve
 * asks for confirmation; Reject opens a required-reason prompt. On success the
 * row is removed and a toast is shown. An empty queue shows a friendly message.
 */
@Component({
  selector: 'admin-approval-queue',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeaderComponent,
    PaginationComponent,
    StatusBadgeComponent,
    StatePanelComponent,
    DensityToggleComponent,
    RowActionsMenuComponent,
  ],
  templateUrl: './approval-queue.component.html',
  styleUrl: './approval-queue.component.css',
})
export class ApprovalQueueComponent implements OnInit, OnDestroy {
  private readonly service = inject(ApprovalService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  protected readonly events = inject(AdminEventsService);

  protected readonly queue = signal<ApprovalQueueItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly toast = signal<Toast | null>(null);
  protected readonly acting = signal(false);

  // --- Client-side paging -------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('approvalQueue', 10));
  protected readonly totalElements = computed(() => this.queue().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed<ApprovalQueueItem[]>(() => {
    const s = this.page() * this.size();
    return this.queue().slice(s, s + this.size());
  });

  /** True when a live status change may have altered the pending queue (A3). */
  protected readonly newActivity = signal(false);
  private activityInitialised = false;
  private lastSeenActivityTs = 0;

  /** The order shown in the detail drawer, or null when closed. */
  protected readonly selected = signal<ApprovalQueueItem | null>(null);
  protected readonly screenshotUrl = signal<string | null>(null);
  protected readonly screenshotLoading = signal(false);
  protected readonly screenshotMissing = signal(false);

  /** True while the invoice PDF is being fetched (review drawer). */
  protected readonly invoiceLoading = signal(false);

  /** The order being rejected (reason modal open), or null. */
  protected readonly rejectTarget = signal<ApprovalQueueItem | null>(null);
  protected readonly rejectForm = this.fb.nonNullable.group({
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  private toastTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    // A status change elsewhere may add/remove pending orders here; surface a
    // subtle, non-disruptive refresh pill rather than reloading mid-review.
    effect(() => {
      const notes = this.events.notifications();
      const relevant = notes.filter((n) => n.type === 'ORDER_STATUS_CHANGED');
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
    this.load();
    // Reuse the shell's singleton SSE stream (idempotent; no second source).
    this.events.connect();
  }

  /** Refetches the queue after a live-activity nudge. */
  refreshFromActivity(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.revokeScreenshot();
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.queue().subscribe({
      next: (items) => {
        this.queue.set(items);
        this.page.set(0);
        this.loading.set(false);
        // Now in sync with the live feed: hide the activity pill.
        const relevant = this.events
          .notifications()
          .filter((n) => n.type === 'ORDER_STATUS_CHANGED');
        this.lastSeenActivityTs = relevant.length ? relevant[0].receivedAt.getTime() : 0;
        this.newActivity.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the approval queue. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Short "2× Neem Oil, 1× Tulsi" style summary of an order's items. */
  itemsSummary(item: ApprovalQueueItem): string {
    if (!item.items?.length) {
      return '—';
    }
    const parts = item.items.map((li) => `${li.quantity}× ${li.productName}`);
    const shown = parts.slice(0, 2).join(', ');
    return parts.length > 2 ? `${shown} +${parts.length - 2} more` : shown;
  }

  money(value: Money | undefined): string {
    if (value === undefined || value === null) {
      return '₹0.00';
    }
    return `₹${value}`;
  }

  /** Up-to-two-letter initials from a customer name, for the drawer avatar. */
  initials(name: string | undefined): string {
    const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '?';
    }
    const first = parts[0][0] ?? '';
    const last = parts.length > 1 ? parts[parts.length - 1][0] ?? '' : '';
    return (first + last).toUpperCase();
  }

  /** Humanises an enum status (e.g. PARTIALLY_PAID → "Partially Paid") for the pill. */
  formatStatus(status: string | undefined): string {
    if (!status) {
      return '';
    }
    return status
      .toLowerCase()
      .split('_')
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ');
  }

  /** A Google Maps search link for an order's shipping address. */
  mapsUrl(item: ApprovalQueueItem): string {
    const query = `${item.addressLine}, ${item.city}, ${item.state} ${item.postalCode}`;
    return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`;
  }

  // --- Detail drawer ------------------------------------------------------

  openDetail(item: ApprovalQueueItem): void {
    this.selected.set(item);
    this.loadScreenshot(item);
  }

  /** Per-row kebab actions (row click opens the detail, so it isn't repeated here). */
  rowActions(): RowAction[] {
    return [
      { key: 'approve', label: 'Approve', icon: 'ti-check', variant: 'success', disabled: this.acting() },
      { key: 'reject', label: 'Reject', icon: 'ti-x', variant: 'danger', disabled: this.acting() },
    ];
  }

  /** Dispatches a kebab action for the given row. */
  onRowAction(key: string, item: ApprovalQueueItem): void {
    if (key === 'approve') {
      void this.approve(item);
    } else if (key === 'reject') {
      this.openReject(item);
    }
  }

  closeDetail(): void {
    this.selected.set(null);
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
  }

  private loadScreenshot(item: ApprovalQueueItem): void {
    this.revokeScreenshot();
    this.screenshotMissing.set(false);
    if (!item.paymentScreenshotAvailable) {
      return;
    }
    this.screenshotLoading.set(true);
    this.service.paymentScreenshot(item.id).subscribe({
      next: (blob) => {
        this.screenshotUrl.set(URL.createObjectURL(blob));
        this.screenshotLoading.set(false);
      },
      error: () => {
        this.screenshotMissing.set(true);
        this.screenshotLoading.set(false);
      },
    });
  }

  private revokeScreenshot(): void {
    const url = this.screenshotUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.screenshotUrl.set(null);
  }

  /** Download / open the PDF invoice for the reviewed order (auth-token blob fetch). */
  downloadInvoice(item: ApprovalQueueItem): void {
    if (this.invoiceLoading()) {
      return;
    }
    this.invoiceLoading.set(true);
    this.service.invoice(item.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = `invoice-${item.orderCode}.pdf`;
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.invoiceLoading.set(false);
      },
      error: () => {
        this.invoiceLoading.set(false);
        this.showToast('error', `Could not generate the invoice for ${item.orderCode}.`);
      },
    });
  }

  // --- Approve ------------------------------------------------------------

  async approve(item: ApprovalQueueItem): Promise<void> {
    if (this.acting()) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Approve order',
      message: `Approve order ${item.orderCode} for ${item.customerName}? This moves it into fulfilment.`,
      confirmLabel: 'Approve',
      icon: 'ti-check',
    });
    if (!confirmed) {
      return;
    }
    this.acting.set(true);
    this.service.approve(item.id).subscribe({
      next: () => {
        this.removeRow(item.id);
        this.acting.set(false);
        this.showToast('ok', `Order ${item.orderCode} approved.`);
        if (this.selected()?.id === item.id) {
          this.closeDetail();
        }
      },
      error: () => {
        this.acting.set(false);
        this.showToast('error', `Could not approve order ${item.orderCode}.`);
      },
    });
  }

  // --- Reject -------------------------------------------------------------

  openReject(item: ApprovalQueueItem): void {
    this.rejectForm.reset({ reason: '' });
    this.rejectTarget.set(item);
  }

  cancelReject(): void {
    this.rejectTarget.set(null);
  }

  confirmReject(): void {
    const item = this.rejectTarget();
    if (!item || this.acting()) {
      return;
    }
    if (this.rejectForm.invalid) {
      this.rejectForm.markAllAsTouched();
      return;
    }
    const reason = this.rejectForm.getRawValue().reason.trim();
    if (!reason) {
      this.rejectForm.markAllAsTouched();
      return;
    }
    this.acting.set(true);
    this.service.reject(item.id, reason).subscribe({
      next: () => {
        this.removeRow(item.id);
        this.acting.set(false);
        this.rejectTarget.set(null);
        this.showToast('ok', `Order ${item.orderCode} rejected.`);
        if (this.selected()?.id === item.id) {
          this.closeDetail();
        }
      },
      error: () => {
        this.acting.set(false);
        this.showToast('error', `Could not reject order ${item.orderCode}.`);
      },
    });
  }

  // --- Helpers ------------------------------------------------------------

  private removeRow(id: number): void {
    this.queue.update((items) => items.filter((i) => i.id !== id));
    // Avoid being stranded on a now-empty trailing page.
    const maxPage = Math.max(0, this.totalPages() - 1);
    if (this.page() > maxPage) {
      this.page.set(maxPage);
    }
  }

  // --- Paging handlers ----------------------------------------------------
  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('approvalQueue', s);
    this.page.set(0);
  }

  private showToast(kind: Toast['kind'], text: string): void {
    this.toast.set({ kind, text });
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), 4000);
  }
}
