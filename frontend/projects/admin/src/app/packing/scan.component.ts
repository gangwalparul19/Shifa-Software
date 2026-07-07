import { DatePipe } from '@angular/common';
import { AfterViewInit, Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from 'core';
import { PackingService } from './packing.service';
import { PackingScanResponse, ScanLogEntry, ScanOutcome } from './packing.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatusBadgeComponent } from '../shared/status-badge.component';

/** The current banner shown above the input after a scan. */
interface ScanBanner {
  outcome: ScanOutcome;
  title: string;
  detail: string;
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
export class ScanComponent implements AfterViewInit {
  private readonly service = inject(PackingService);
  private readonly fb = inject(FormBuilder);

  @ViewChild('barcodeInput') private barcodeInput?: ElementRef<HTMLInputElement>;

  protected readonly form = this.fb.nonNullable.group({
    barcode: ['', [Validators.required]],
  });

  protected readonly submitting = signal(false);
  protected readonly banner = signal<ScanBanner | null>(null);
  protected readonly log = signal<ScanLogEntry[]>([]);

  ngAfterViewInit(): void {
    this.focusInput();
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
    this.finish();
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
