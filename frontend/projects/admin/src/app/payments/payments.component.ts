import { IstDatePipe } from '../shared/ist-date.pipe';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { InrPipe } from '../shared/inr.pipe';
import { ToastService } from '../shared/toast.service';
import {
  SourceFilterMode,
  matchesSourceMode,
  readSourceFilter,
  writeSourceFilter,
} from '../shared/source-filter.util';
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
  imports: [
    IstDatePipe,
    RouterLink,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    InrPipe,
  ],
  templateUrl: './payments.component.html',
  styleUrl: './payments.component.css',
})
export class PaymentsComponent implements OnInit, OnDestroy {
  private readonly service = inject(PaymentsService);
  private readonly toasts = inject(ToastService);

  protected readonly rowsRaw = signal<PaymentQueueRow[]>([]);

  // --- Source filter (Portal / Shopify / All, default Portal) -------------
  protected readonly sourceFilter = signal<SourceFilterMode>(readSourceFilter('shifa:payments-source'));

  setSourceFilter(mode: SourceFilterMode): void {
    this.sourceFilter.set(mode);
    writeSourceFilter('shifa:payments-source', mode);
    this.page.set(0);
  }

  /** Source-filtered payment queue. */
  protected readonly rows = computed(() =>
    this.rowsRaw().filter((r) => matchesSourceMode(r.source, this.sourceFilter())),
  );

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busyId = signal<number | null>(null);

  // --- Client-side paging -------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('paymentsQueue', 10));
  protected readonly totalElements = computed(() => this.rows().length);
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.totalElements() / this.size())));
  protected readonly pageItems = computed<PaymentQueueRow[]>(() => {
    const s = this.page() * this.size();
    return this.rows().slice(s, s + this.size());
  });

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('paymentsQueue', s);
    this.page.set(0);
  }

  /**
   * Every proof for the order currently open in the viewer, as object URLs (V65).
   * A verifier needs to see ALL the proof for a payment — a part payment plus the
   * balance, or a UPI receipt plus a bank confirmation — not just the first one,
   * since the decision is about whether the received amount is genuine.
   */
  protected readonly screenshotUrls = signal<string[]>([]);
  protected readonly screenshotLoading = signal(false);
  /** Index of the proof shown in the viewer, driven by the Snip tabs (V65). */
  protected readonly activeSnip = signal(0);

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
        this.rowsRaw.set(rows);
        const maxPage = Math.max(0, this.totalPages() - 1);
        if (this.page() > maxPage) {
          this.page.set(maxPage);
        }
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the payment queue. Please try again.');
        this.loading.set(false);
      },
    });
  }

  // --- Screenshot viewer --------------------------------------------------

  /** The order code shown in the screenshot viewer's title (current row or a duplicate). */
  protected readonly screenshotTitle = signal<string>('');

  /**
   * Opens every payment proof on file for the current queue row (V65). Delegates
   * to {@link #openScreenshotsFor}.
   */
  viewScreenshot(row: PaymentQueueRow): void {
    this.openScreenshotsFor(row.id, row.orderCode, row.paymentScreenshotAvailable);
  }

  /**
   * Opens the payment screenshot(s) of a DUPLICATE order (the other order sharing
   * this proof) so the verifier can compare them side-by-side without leaving the
   * queue. The duplicate's screenshot is assumed available (it shares the proof).
   */
  viewDuplicateScreenshot(dup: { orderId: number; orderCode: string }): void {
    this.openScreenshotsFor(dup.orderId, dup.orderCode, true);
  }

  /**
   * Opens every payment proof on file for the given order id (V65). Proofs are
   * enumerated first, then fetched; one unreadable proof is skipped rather than
   * failing the whole set. If the listing is unavailable we fall back to the
   * legacy single-proof endpoint so the viewer still works.
   */
  private openScreenshotsFor(orderId: number, orderCode: string, available: boolean): void {
    if (!available) {
      return;
    }
    this.screenshotTitle.set(orderCode);
    this.screenshotLoading.set(true);
    this.service.screenshots(orderId).subscribe({
      next: (shots) => {
        if (shots.length === 0) {
          this.loadPrimaryScreenshot(orderId);
          return;
        }
        forkJoin(
          shots.map((shot) =>
            this.service.screenshotById(orderId, shot.id).pipe(catchError(() => of(null))),
          ),
        ).subscribe((blobs) => {
          const urls = blobs
            .filter((blob): blob is Blob => blob !== null)
            .map((blob) => URL.createObjectURL(blob));
          this.screenshotLoading.set(false);
          if (urls.length === 0) {
            this.toasts.error('Could not load the payment screenshots.');
            return;
          }
          this.revokeScreenshot();
          this.screenshotUrls.set(urls);
          this.activeSnip.set(0);
        });
      },
      error: () => this.loadPrimaryScreenshot(orderId),
    });
  }

  /** Fallback to the pre-V65 single-proof endpoint. */
  private loadPrimaryScreenshot(orderId: number): void {
    this.service.screenshot(orderId).subscribe({
      next: (blob) => {
        this.revokeScreenshot();
        this.screenshotUrls.set([URL.createObjectURL(blob)]);
        this.activeSnip.set(0);
        this.screenshotLoading.set(false);
      },
      error: () => {
        this.screenshotLoading.set(false);
        this.toasts.error('Could not load the payment screenshot.');
      },
    });
  }

  /** Shows the proof at the given tab index. */
  selectSnip(index: number): void {
    this.activeSnip.set(index);
  }

  closeScreenshot(): void {
    this.revokeScreenshot();
  }

  private revokeScreenshot(): void {
    for (const url of this.screenshotUrls()) {
      URL.revokeObjectURL(url);
    }
    this.screenshotUrls.set([]);
    this.activeSnip.set(0);
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
