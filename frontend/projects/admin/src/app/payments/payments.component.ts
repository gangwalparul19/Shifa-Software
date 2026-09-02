import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { InrPipe } from '../shared/inr.pipe';
import { ToastService } from '../shared/toast.service';
import { PaymentsService } from './payments.service';
import { PaymentQueueRow } from './payments.model';

/**
 * Payment Verifier dashboard (PAYMENT_VERIFIER + ADMIN, product-audit §4.4).
 *
 * <p>Lists prepaid payments awaiting an authenticity check, lets the verifier
 * view the payment screenshot against the amount, and record a Verify / Reject
 * decision (with an optional note). Decisions do not change the order status —
 * they are an additive verification layer surfaced to the admin approval flow.
 */
@Component({
  selector: 'admin-payments',
  imports: [DatePipe, PageHeaderComponent, StatePanelComponent, InrPipe],
  templateUrl: './payments.component.html',
  styleUrl: './payments.component.css',
})
export class PaymentsComponent implements OnInit, OnDestroy {
  private readonly service = inject(PaymentsService);
  private readonly toasts = inject(ToastService);

  protected readonly rows = signal<PaymentQueueRow[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busyId = signal<number | null>(null);

  /** The screenshot currently open in the viewer (object URL), or null. */
  protected readonly screenshotUrl = signal<string | null>(null);
  protected readonly screenshotLoading = signal(false);

  /** The row whose Verify/Reject decision modal is open, plus the decision kind. */
  protected readonly decision = signal<{ row: PaymentQueueRow; kind: 'verify' | 'reject' } | null>(null);

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.revokeScreenshot();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.queue().subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the payment queue. Please try again.');
        this.loading.set(false);
      },
    });
  }

  // --- Screenshot viewer --------------------------------------------------

  viewScreenshot(row: PaymentQueueRow): void {
    if (!row.paymentScreenshotAvailable) {
      return;
    }
    this.screenshotLoading.set(true);
    this.service.screenshot(row.id).subscribe({
      next: (blob) => {
        this.revokeScreenshot();
        this.screenshotUrl.set(URL.createObjectURL(blob));
        this.screenshotLoading.set(false);
      },
      error: () => {
        this.screenshotLoading.set(false);
        this.toasts.error('Could not load the payment screenshot.');
      },
    });
  }

  closeScreenshot(): void {
    this.revokeScreenshot();
  }

  private revokeScreenshot(): void {
    const url = this.screenshotUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.screenshotUrl.set(null);
  }

  // --- Verify / Reject ----------------------------------------------------

  openDecision(row: PaymentQueueRow, kind: 'verify' | 'reject'): void {
    this.decision.set({ row, kind });
  }

  cancelDecision(): void {
    this.decision.set(null);
  }

  confirmDecision(note: string): void {
    const pending = this.decision();
    if (!pending) {
      return;
    }
    this.decision.set(null);
    this.busyId.set(pending.row.id);
    const call =
      pending.kind === 'verify'
        ? this.service.verify(pending.row.id, note)
        : this.service.reject(pending.row.id, note);
    call.subscribe({
      next: () => {
        this.busyId.set(null);
        this.toasts.success(
          pending.kind === 'verify'
            ? `Payment for ${pending.row.orderCode} verified`
            : `Payment for ${pending.row.orderCode} rejected`,
        );
        this.load();
      },
      error: () => {
        this.busyId.set(null);
        this.toasts.error('Could not record the decision. Please try again.');
      },
    });
  }
}
