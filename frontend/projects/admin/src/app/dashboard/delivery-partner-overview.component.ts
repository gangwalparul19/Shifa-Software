import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CompactInrPipe, InrPipe } from '../shared/inr.pipe';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import {
  PeriodSelection,
  THIS_MONTH,
  isUsableSelection,
  periodDates,
  periodLabel as selectionLabel,
} from '../shared/period-selection';
import { DashboardService } from './dashboard.service';
import { DeliveryPartnerSummary, PartnerStats } from './delivery-partner.model';

/** One partner card's display config (key → colour + icon + how to drill into Orders). */
interface PartnerCardMeta {
  key: 'quikShipX' | 'inHouse' | 'pos';
  color: string;
  icon: string;
}

/**
 * ADMIN-only "Orders by delivery partner" dashboard section. Differentiates the
 * three fulfilment partners — QuikShipX (courier), In-house (Ishika Enterprise),
 * and POS (store) — each with its own card showing orders in transit, delivered,
 * cancelled, COD still to collect, revenue, and a per-stage breakdown. Backed by
 * {@code GET /api/admin/orders/delivery-partner-summary}.
 */
@Component({
  selector: 'admin-delivery-partner-overview',
  standalone: true,
  imports: [InrPipe, CompactInrPipe, PeriodPickerComponent],
  templateUrl: './delivery-partner-overview.component.html',
  styleUrl: './delivery-partner-overview.component.css',
})
export class DeliveryPartnerOverviewComponent implements OnInit {
  private readonly service = inject(DashboardService);
  private readonly router = inject(Router);

  protected readonly data = signal<DeliveryPartnerSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** The period shown: this month until the user picks another. */
  protected readonly selection = signal<PeriodSelection>(THIS_MONTH);
  /** Numbers the requests so a slow answer for an earlier choice cannot overwrite a newer one. */
  private requestId = 0;

  /** Per-partner visual style + drill config, in display order. */
  protected readonly cardMeta: PartnerCardMeta[] = [
    { key: 'quikShipX', color: '#206bc4', icon: 'ti-truck-delivery' },
    { key: 'inHouse', color: '#1f5d3f', icon: 'ti-home-move' },
    { key: 'pos', color: '#9c6ade', icon: 'ti-building-store' },
  ];

  /** The three partner cards resolved from the loaded summary (null until loaded). */
  protected readonly cards = computed<{ meta: PartnerCardMeta; stats: PartnerStats }[]>(() => {
    const d = this.data();
    if (!d) {
      return [];
    }
    return this.cardMeta.map((meta) => ({ meta, stats: d[meta.key] }));
  });

  protected readonly periodLabel = computed(() => selectionLabel(this.selection()));

  ngOnInit(): void {
    this.load();
  }

  /** A new period was picked: reload the partner cards for it. */
  choose(selection: PeriodSelection): void {
    this.selection.set(selection);
    this.load();
  }

  load(): void {
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      return; // a half-typed custom range: wait for a valid one
    }
    const request = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    // India-time days (null/null = all time); the browser's own timezone plays no part.
    const dates = periodDates(selection);
    this.service.deliveryPartnerSummary(dates?.from ?? null, dates?.to ?? null).subscribe({
      next: (d) => {
        if (request !== this.requestId) {
          return;
        }
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        if (request !== this.requestId) {
          return;
        }
        this.error.set('Could not load the delivery-partner summary. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Delivery-success % for a partner (delivered / (delivered + failed)), or null when none. */
  successPct(s: PartnerStats): number | null {
    const base = s.delivered + s.failedReturned;
    return base === 0 ? null : Math.round((s.delivered / base) * 100);
  }

  successClass(s: PartnerStats): string {
    const pct = this.successPct(s);
    if (pct === null) {
      return 'tone-grey';
    }
    return pct >= 80 ? 'tone-green' : pct >= 50 ? 'tone-amber' : 'tone-red';
  }

  /**
   * Opens the Orders page filtered to the partner (by source where the backend
   * supports it) and optionally a lifecycle stage. POS → source=STORE; QuikShipX
   * and in-house share the salesperson/own-order source, so those drill by stage
   * only (the partner split isn't a server Orders filter yet).
   */
  drill(partner: 'quikShipX' | 'inHouse' | 'pos', statusGroup?: string): void {
    const queryParams: Record<string, string> = {};
    if (partner === 'pos') {
      queryParams['source'] = 'STORE';
    }
    if (statusGroup) {
      queryParams['statusGroup'] = statusGroup;
    }
    void this.router.navigate(['/orders'], { queryParams });
  }

  num(v: number | string | null | undefined): number {
    const n = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(n) ? n : 0;
  }
}
