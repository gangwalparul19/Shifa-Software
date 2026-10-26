import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ApiError } from 'core';
import { PackingService } from './packing.service';
import { RtoReason, RtoScanPreviewResponse, ScannedOrderSummary } from './packing.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';
import { ToastService } from '../shared/toast.service';
import { CameraScannerComponent } from './camera-scanner.component';
import { openWhatsApp } from '../shared/whatsapp.util';

/** How a single RTO scan resolved, for the in-session log. */
type RtoOutcome = 'marked' | 'not-eligible' | 'not-recognized' | 'error';

/** One entry in the running RTO log shown in the UI. */
interface RtoLogEntry {
  barcode: string;
  outcome: RtoOutcome;
  message: string;
  at: Date;
}

/**
 * RTO (returned to origin) scan page (label redesign feature).
 *
 * <p>Scanning a returned parcel opens a review modal. The operator can add
 * comments and explicitly confirm the RTO mark; the backend records the
 * catch-all {@code OTHER} reason because the barcode itself cannot reliably
 * identify the delivery-failure category. An ineligible scan remains visible
 * with the current status so the operator knows why it was not changed.
 */
@Component({
  selector: 'admin-rto-scan',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    StatusBadgeComponent,
    CameraScannerComponent,
  ],
  templateUrl: './rto.component.html',
  styleUrl: './rto.component.css',
})
export class RtoComponent implements OnInit {
  private readonly service = inject(PackingService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);

  @ViewChild('barcodeInput') private barcodeInput?: ElementRef<HTMLInputElement>;

  protected readonly form = this.fb.nonNullable.group({
    barcode: ['', [Validators.required]],
  });

  /** Details entered after scanning and before marking the order RTO. */
  protected readonly noteForm = this.fb.nonNullable.group({
    note: ['', [Validators.maxLength(500)]],
  });

  protected readonly submitting = signal(false);
  protected readonly marking = signal(false);
  protected readonly cameraOpen = signal(false);
  protected readonly log = signal<RtoLogEntry[]>([]);

  /** A scanned order awaiting comments and explicit RTO confirmation. */
  protected readonly pendingPreview = signal<RtoScanPreviewResponse | null>(null);

  ngOnInit(): void {
    this.focusInput();
  }

  /** Resolves a typed or handheld-scanned barcode before any status mutation. */
  submit(): void {
    if (this.submitting() || this.marking() || this.pendingPreview()) {
      return;
    }
    const barcode = this.form.getRawValue().barcode.trim();
    if (!barcode) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.service.rtoPreview(barcode).subscribe({
      next: (preview) => {
        this.submitting.set(false);
        this.pendingPreview.set(preview);
        this.noteForm.reset({ note: '' });
        if (!preview.eligible) {
          this.toasts.info(preview.message);
        }
      },
      error: (err: HttpErrorResponse) => this.onPreviewError(barcode, err),
    });
  }

  /** Cancels a scan preview without changing the order. */
  cancelPreview(): void {
    this.pendingPreview.set(null);
    this.noteForm.reset({ note: '' });
    this.finish();
  }

  /** Confirms marking the scanned order RTO after entering optional comments. */
  confirmMarkRto(): void {
    const preview = this.pendingPreview();
    if (!preview || !preview.eligible || this.marking()) {
      return;
    }
    if (this.noteForm.invalid) {
      this.noteForm.markAllAsTouched();
      return;
    }
    this.markPreview(preview, 'OTHER', this.noteForm.getRawValue().note);
  }

  /** Applies the RTO transition after the operator confirms the scan. */
  private markPreview(preview: RtoScanPreviewResponse, reason: RtoReason, note: string): void {
    if (this.marking()) {
      return;
    }
    this.marking.set(true);
    this.service.markRto(preview.order.id, reason, note).subscribe({
      next: () => {
        this.marking.set(false);
        this.pendingPreview.set(null);
        this.toasts.success(`Order ${preview.order.orderCode} marked RTO. You can now retry it from Orders.`);
        this.appendLog({
          barcode: preview.order.orderCode,
          outcome: 'marked',
          message: 'Marked RTO — ready for a retry order',
          at: new Date(),
        });
        this.finish();
      },
      error: (err: HttpErrorResponse) => {
        this.marking.set(false);
        const apiError = err.error as ApiError | undefined;
        const message = apiError?.message ?? 'Could not mark the order RTO. Please try again.';
        this.toasts.error(message);
        this.appendLog({
          barcode: preview.order.orderCode,
          outcome: err.status === 409 ? 'not-eligible' : 'error',
          message,
          at: new Date(),
        });
        this.pendingPreview.set(null);
        this.finish();
      },
    });
  }

  /** Opens the order detail (via the Orders page filtered to this order code). */
  openOrder(order: ScannedOrderSummary): void {
    void this.router.navigate(['/orders'], { queryParams: { q: order.orderCode } });
  }

  /**
   * Opens WhatsApp for the previewed order's customer with a short RTO
   * notification message (click-to-WhatsApp, no API needed). Uses BMP-safe
   * symbols only, matching the app-wide WhatsApp templates.
   */
  messageCustomer(order: ScannedOrderSummary): void {
    const message =
      `Hi! ☘ This is Shifa Herbal Remedies.\n\n` +
      `Your order ${order.orderCode} could not be delivered and is on its way back to us. ` +
      `We would love to get it to you — please reply here to arrange redelivery or a refund. ❤`;
    const ok = openWhatsApp(order.customerMobile, message);
    if (!ok) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }

  formatStatus(status: string | undefined | null): string {
    return status ? status.replaceAll('_', ' ') : '';
  }

  // --- Camera scan ----------------------------------------------------------

  openCamera(): void {
    if (!this.submitting() && !this.marking() && !this.pendingPreview()) {
      this.cameraOpen.set(true);
    }
  }

  closeCamera(): void {
    this.cameraOpen.set(false);
  }

  onCameraScanned(code: string): void {
    this.cameraOpen.set(false);
    this.form.controls.barcode.setValue(code.trim());
    this.submit();
  }

  /** Clear the running scan log. */
  clearLog(): void {
    this.log.set([]);
  }

  // --- Outcome handling -------------------------------------------------

  private onPreviewError(barcode: string, err: HttpErrorResponse): void {
    this.submitting.set(false);
    const apiError = err.error as ApiError | undefined;
    const code = apiError?.code;

    if (code === 'BARCODE_NOT_RECOGNIZED' || err.status === 404) {
      const message = `No order matches "${barcode}". Check the label and try again.`;
      this.toasts.error(message);
      this.appendLog({ barcode, outcome: 'not-recognized', message, at: new Date() });
    } else {
      const message = apiError?.message ?? 'Something went wrong. Please try again.';
      this.toasts.error(message);
      this.appendLog({ barcode, outcome: 'error', message, at: new Date() });
    }
    this.finish();
  }

  private appendLog(entry: RtoLogEntry): void {
    this.log.update((rows) => [entry, ...rows].slice(0, 20));
  }

  private finish(): void {
    this.form.reset({ barcode: '' });
    this.noteForm.reset({ note: '' });
    this.focusInput();
  }

  private focusInput(): void {
    setTimeout(() => this.barcodeInput?.nativeElement.focus({ preventScroll: true }), 0);
  }
}
