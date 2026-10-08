import { Component, ElementRef, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { ApiError } from 'core';
import { PackingService } from './packing.service';
import {
  RTO_REASON_OPTIONS,
  RtoReason,
  RtoScanPreviewResponse,
  ScannedOrderSummary,
} from './packing.model';
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
 * <p>The order barcode printed on the redesigned internal label is always our
 * own order code — regardless of whether a courier/AWB was ever allotted — so
 * when a parcel physically comes back to the godown, the packer scans that
 * barcode here. The flow mirrors the packing "Scan & Move" preview/confirm
 * pattern: {@code POST /api/packing/rto-preview} resolves the barcode
 * read-only and reports whether marking it RTO is currently a legal move; only
 * on explicit confirmation with a REQUIRED categorized reason (+ optional
 * note) does {@code POST /api/packing/{id}/rto} actually mark the order.
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

  protected readonly reasonOptions = RTO_REASON_OPTIONS;

  protected readonly form = this.fb.nonNullable.group({
    barcode: ['', [Validators.required]],
  });

  protected readonly reasonForm = this.fb.nonNullable.group({
    reason: ['' as '' | RtoReason, [Validators.required]],
    note: ['', [Validators.maxLength(500)]],
  });

  protected readonly submitting = signal(false);
  protected readonly marking = signal(false);
  protected readonly cameraOpen = signal(false);
  protected readonly log = signal<RtoLogEntry[]>([]);

  /** A scanned order awaiting the packer's reason + explicit confirmation. */
  protected readonly pendingPreview = signal<RtoScanPreviewResponse | null>(null);

  ngOnInit(): void {
    this.focusInput();
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
    this.service.rtoPreview(barcode).subscribe({
      next: (preview) => {
        this.submitting.set(false);
        this.pendingPreview.set(preview);
        this.reasonForm.reset({ reason: '', note: '' });
      },
      error: (err: HttpErrorResponse) => this.onPreviewError(barcode, err),
    });
  }

  /** Cancels a scan preview without changing the order. */
  cancelPreview(): void {
    this.pendingPreview.set(null);
    this.finish();
  }

  /** Confirms marking the previewed order RTO with the chosen reason + note. */
  confirmMarkRto(): void {
    const preview = this.pendingPreview();
    if (!preview || !preview.eligible || this.marking()) {
      return;
    }
    if (this.reasonForm.invalid) {
      this.reasonForm.markAllAsTouched();
      return;
    }
    const { reason, note } = this.reasonForm.getRawValue();
    if (!reason) {
      return;
    }

    this.marking.set(true);
    this.service.markRto(preview.order.id, reason, note).subscribe({
      next: () => {
        this.marking.set(false);
        this.pendingPreview.set(null);
        this.toasts.success(
          `Order ${preview.order.orderCode} marked RTO. A sales return was recorded automatically.`,
        );
        this.appendLog({
          barcode: preview.order.orderCode,
          outcome: 'marked',
          message: `Marked RTO (${this.reasonLabel(reason)}) — sales return recorded`,
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

  reasonLabel(reason: RtoReason): string {
    return this.reasonOptions.find((o) => o.value === reason)?.label ?? reason;
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
    if (!this.submitting() && !this.pendingPreview()) {
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
    this.focusInput();
  }

  private focusInput(): void {
    setTimeout(() => this.barcodeInput?.nativeElement.focus({ preventScroll: true }), 0);
  }
}
