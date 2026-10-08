import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ChannelLogoComponent } from '../shared/channel-logo.component';
import { ToastService } from '../shared/toast.service';
import { OrdersService } from '../orders/orders.service';
import { RecoverResult, ShopifySyncService, StuckShopifyOrder } from './shopify-sync.service';

/**
 * ADMIN-only Shopify sync page: lists Shopify orders that have not yet reached
 * QuikShipX "Tracking ID Assigned" and offers a one-click Recover that pushes them
 * forward (auto-approve pending ones, re-publish label-generated ones to QuikShipX).
 * QuikShipX assigns tracking ids in the background, so the list refreshes itself
 * shortly after a recover run.
 */
@Component({
  selector: 'admin-shopify-sync',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, RouterLink, PageHeaderComponent, PaginationComponent, StatePanelComponent, ChannelLogoComponent],
  templateUrl: './shopify-sync.component.html',
  styleUrl: './shopify-sync.component.css',
})
export class ShopifySyncComponent implements OnInit, OnDestroy {
  private readonly service = inject(ShopifySyncService);
  private readonly ordersApi = inject(OrdersService);
  private readonly toasts = inject(ToastService);

  /** The order id currently being re-routed to in-house (for a per-row spinner). */
  protected readonly reroutingId = signal<number | null>(null);

  /** Delay before re-checking, giving QuikShipX time to allot tracking ids. */
  private static readonly RECHECK_MS = 30_000;

  protected readonly orders = signal<StuckShopifyOrder[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly recovering = signal(false);
  protected readonly confirming = signal(false);
  protected readonly lastResult = signal<RecoverResult | null>(null);
  protected readonly rechecking = signal(false);
  protected readonly lastChecked = signal<Date | null>(null);

  /** Orders that still genuinely need a tracking id (recoverable + no AWB yet). */
  protected readonly waiting = computed(() => this.orders().filter((o) => o.waiting));
  /** QuikShipX orders that already have a tracking id (in sync — not stuck). */
  protected readonly assigned = computed(
    () => this.orders().filter((o) => o.trackingAssigned && !o.inHouse),
  );
  /** In-house (Ishika Enterprise) orders — never go to QuikShipX. */
  protected readonly inHouse = computed(() => this.orders().filter((o) => o.inHouse));

  // --- Client-side paging for the two lists (10/page each) ----------------
  protected readonly waitingPage = signal(0);
  protected readonly waitingSize = signal(readPageSize('shopifyWaiting', 10));
  protected readonly waitingTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.waiting().length / this.waitingSize())),
  );
  protected readonly waitingPageItems = computed<StuckShopifyOrder[]>(() => {
    const s = this.waitingPage() * this.waitingSize();
    return this.waiting().slice(s, s + this.waitingSize());
  });
  setWaitingPage(p: number): void {
    this.waitingPage.set(p);
  }
  setWaitingSize(s: number): void {
    this.waitingSize.set(s);
    writePageSize('shopifyWaiting', s);
    this.waitingPage.set(0);
  }

  protected readonly assignedPage = signal(0);
  protected readonly assignedSize = signal(readPageSize('shopifyAssigned', 10));
  protected readonly assignedTotalPages = computed(() =>
    Math.max(1, Math.ceil(this.assigned().length / this.assignedSize())),
  );
  protected readonly assignedPageItems = computed<StuckShopifyOrder[]>(() => {
    const s = this.assignedPage() * this.assignedSize();
    return this.assigned().slice(s, s + this.assignedSize());
  });
  setAssignedPage(p: number): void {
    this.assignedPage.set(p);
  }
  setAssignedSize(s: number): void {
    this.assignedSize.set(s);
    writePageSize('shopifyAssigned', s);
    this.assignedPage.set(0);
  }

  protected readonly pendingCount = computed(
    () => this.waiting().filter((o) => o.status === 'PENDING_ADMIN_APPROVAL').length,
  );
  protected readonly labelCount = computed(
    () => this.waiting().filter((o) => o.status === 'LABEL_GENERATED').length,
  );

  private recheckTimer: ReturnType<typeof setTimeout> | null = null;

  // --- Integration switch -------------------------------------------------
  /** Whether incoming Shopify orders are imported; null until loaded. */
  protected readonly integrationOn = signal<boolean | null>(null);
  protected readonly integrationSaving = signal(false);
  protected readonly confirmingOff = signal(false);

  ngOnInit(): void {
    this.loadIntegration();
    this.load();
  }

  private loadIntegration(): void {
    this.service.integration().subscribe({
      next: (s) => this.integrationOn.set(s.enabled),
      error: () => this.toasts.error('Could not load the Shopify integration setting.'),
    });
  }

  /** Switch clicked: turning OFF asks for confirmation first; turning ON applies immediately. */
  toggleIntegration(): void {
    if (this.integrationOn()) {
      this.confirmingOff.set(true);
    } else {
      this.saveIntegration(true);
    }
  }

  cancelTurnOff(): void {
    this.confirmingOff.set(false);
  }

  saveIntegration(enabled: boolean): void {
    this.confirmingOff.set(false);
    this.integrationSaving.set(true);
    this.service.setIntegration(enabled).subscribe({
      next: (s) => {
        this.integrationOn.set(s.enabled);
        this.integrationSaving.set(false);
        this.toasts.success(
          s.enabled
            ? 'Shopify integration is ON. New Shopify orders will come into the portal.'
            : 'Shopify integration is OFF. New Shopify orders will not come into the portal.',
        );
      },
      error: () => {
        this.integrationSaving.set(false);
        this.toasts.error('Could not change the Shopify integration setting. Please try again.');
      },
    });
  }

  ngOnDestroy(): void {
    this.clearRecheck();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.stuck().subscribe({
      next: (rows) => {
        this.orders.set(rows);
        this.waitingPage.set(0);
        this.assignedPage.set(0);
        this.loading.set(false);
        this.lastChecked.set(new Date());
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load Shopify orders. Please try again.');
      },
    });
  }

  askRecover(): void {
    this.confirming.set(true);
  }

  cancelRecover(): void {
    this.confirming.set(false);
  }

  recover(): void {
    this.confirming.set(false);
    this.recovering.set(true);
    this.service.recover().subscribe({
      next: (res) => {
        this.recovering.set(false);
        this.lastResult.set(res);
        const total = res.approvedFromPending + res.republishedFromLabelGenerated;
        if (total === 0) {
          this.toasts.info('Nothing to recover — every Shopify order is already in sync.');
        } else {
          this.toasts.success(`Sent ${total} order(s) to QuikShipX. Tracking ids arrive within a minute.`);
        }
        this.load();
        this.scheduleRecheck();
      },
      error: () => {
        this.recovering.set(false);
        this.toasts.error('Recover failed. Please try again.');
      },
    });
  }

  /** Why an order is still waiting: not yet approved, or approved but no tracking id yet. */
  statusLabel(status: string): string {
    return status === 'PENDING_ADMIN_APPROVAL' ? 'Pending approval' : 'Awaiting tracking ID';
  }

  /**
   * Re-routes a stuck Shopify order to in-house delivery (QuikShipX could not ship
   * it — e.g. a non-serviceable pincode). Switches the delivery method to IN_HOUSE
   * (which detaches it from QuikShipX and clears the failure), so the team hands it
   * to their own / a local partner instead of waiting on a tracking id that will
   * never come. Reloads the list afterwards.
   */
  switchToInHouse(order: StuckShopifyOrder): void {
    if (this.reroutingId() !== null) {
      return;
    }
    this.reroutingId.set(order.id);
    this.ordersApi.updateDeliveryMethod(order.id, 'IN_HOUSE').subscribe({
      next: () => {
        this.reroutingId.set(null);
        this.toasts.success(
          `${order.orderCode} switched to in-house delivery. Fulfil it from Packing — no courier tracking id is needed.`,
        );
        this.load();
      },
      error: () => {
        this.reroutingId.set(null);
        this.toasts.error('Could not switch this order to in-house. Please try again.');
      },
    });
  }

  private scheduleRecheck(): void {
    this.clearRecheck();
    this.rechecking.set(true);
    this.recheckTimer = setTimeout(() => {
      this.rechecking.set(false);
      this.recheckTimer = null;
      this.load();
    }, ShopifySyncComponent.RECHECK_MS);
  }

  private clearRecheck(): void {
    if (this.recheckTimer) {
      clearTimeout(this.recheckTimer);
      this.recheckTimer = null;
    }
    this.rechecking.set(false);
  }
}
