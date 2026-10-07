import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CompactInrPipe, InrPipe } from '../shared/inr.pipe';
import { DashboardService } from './dashboard.service';
import { DeliveryPartnerSummary, PartnerStats } from './delivery-partner.model';

/** The quick period presets for the delivery-partner section. */
type PresetKey = 'this-month' | 'last-30' | 'all';

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
  imports: [InrPipe, CompactInrPipe],
  templateUrl: './delivery-partner-overview.component.html',
  styleUrl: './delivery-partner-overview.component.css',
})
export class DeliveryPartnerOverviewComponent implements OnInit {
  private readonly service = inject(DashboardService);
  private readonly router = inject(Router);

  protected readonly data = signal<DeliveryPartnerSummary | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly activePreset = signal<PresetKey>('this-month');

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

  protected readonly periodLabel = computed(() => {
    switch (this.activePreset()) {
      case 'this-month':
        return 'This month';
      case 'last-30':
        return 'Last 30 days';
      default:
        return 'All time';
    }
  });

  ngOnInit(): void {
    this.load();
  }

  setPreset(preset: PresetKey): void {
    if (this.activePreset() === preset) {
      return;
    }
    this.activePreset.set(preset);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const { from, to } = this.window();
    this.service.deliveryPartnerSummary(from, to).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the delivery-partner summary. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** The ISO date window for the active preset (null/null = all-time). */
  private window(): { from: string | null; to: string | null } {
    const today = new Date();
    const iso = (d: Date) => {
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, '0');
      const day = String(d.getDate()).padStart(2, '0');
      return `${y}-${m}-${day}`;
    };
    switch (this.activePreset()) {
      case 'this-month':
        return { from: iso(new Date(today.getFullYear(), today.getMonth(), 1)), to: iso(today) };
      case 'last-30': {
        const start = new Date(today);
        start.setDate(start.getDate() - 29);
        return { from: iso(start), to: iso(today) };
      }
      default:
        return { from: null, to: null };
    }
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
