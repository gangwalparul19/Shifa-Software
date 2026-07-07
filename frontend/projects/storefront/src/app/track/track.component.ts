import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OrderTracking, TrackService } from './track.service';

/**
 * Order tracking page (routes 'track' and 'track/:orderCode', Requirement 13.4).
 *
 * <p>A customer enters (or opens a link containing) their order code and sees
 * the current Order_Status, AWB, courier name, and a courier tracking link,
 * fetched from the public {@code GET /api/track/{orderCode}} endpoint. A
 * not-found response is handled gracefully with a friendly message rather than
 * an error state.
 */
@Component({
  selector: 'sf-track',
  imports: [ReactiveFormsModule],
  templateUrl: './track.component.html',
  styleUrl: './track.component.css',
})
export class TrackComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly track = inject(TrackService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly loading = signal(false);
  protected readonly notFound = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly result = signal<OrderTracking | null>(null);
  protected readonly invoiceLoading = signal(false);
  protected readonly invoiceError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    orderCode: ['', [Validators.required]],
  });

  ngOnInit(): void {
    const code = this.route.snapshot.paramMap.get('orderCode');
    if (code) {
      this.form.controls.orderCode.setValue(code);
      this.lookup(code);
    }
  }

  /** Submits the tracking form, updating the URL so the lookup is shareable. */
  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const code = this.form.getRawValue().orderCode.trim();
    if (!code) {
      return;
    }
    // Reflect the code in the URL (Req 13.4 — a customer can open their order).
    void this.router.navigate(['/track', code]);
    this.lookup(code);
  }

  /** Human-readable label for an Order_Status enum value (underscores -> spaces). */
  statusLabel(status: string | null | undefined): string {
    return (status ?? '').replace(/_/g, ' ');
  }

  /** True once the parcel is on its way, used to highlight the tracking link. */
  hasTracking(t: OrderTracking): boolean {
    return !!(t.awb && t.trackingUrl);
  }

  /**
   * Downloads / opens the PDF invoice for the tracked order from the public
   * endpoint. Fetches the PDF as a Blob, opens it in a new tab (falling back to
   * a download link if pop-ups are blocked), then revokes the temporary URL.
   */
  downloadInvoice(t: OrderTracking): void {
    this.invoiceLoading.set(true);
    this.invoiceError.set(null);
    this.track.invoice(t.orderCode).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const opened = window.open(url, '_blank');
        if (!opened) {
          const a = document.createElement('a');
          a.href = url;
          a.download = `invoice-${t.orderCode}.pdf`;
          a.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.invoiceLoading.set(false);
      },
      error: () => {
        this.invoiceLoading.set(false);
        this.invoiceError.set('Sorry, we could not generate your invoice right now. Please try again.');
      },
    });
  }

  private lookup(code: string): void {
    this.loading.set(true);
    this.notFound.set(false);
    this.errorMessage.set(null);
    this.result.set(null);

    this.track.track(code).subscribe({
      next: (tracking) => {
        this.loading.set(false);
        this.result.set(tracking);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        if (err.status === 404) {
          // Req 13.4 — handle a missing order gracefully.
          this.notFound.set(true);
        } else {
          this.errorMessage.set(
            'We could not fetch tracking details right now. Please try again in a moment.',
          );
        }
      },
    });
  }
}
