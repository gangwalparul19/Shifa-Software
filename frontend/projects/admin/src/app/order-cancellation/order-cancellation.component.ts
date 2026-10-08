import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { CurrencyPipe } from '@angular/common';
import { OrderStatus } from 'core';
import { OrdersService } from '../orders/orders.service';
import { OrderSummary } from '../orders/orders.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { StatusBadgeComponent, humanizeStatus } from '../shared/status-badge.component';
import { ChannelLogoComponent } from '../shared/channel-logo.component';
import { PaginationComponent } from '../shared/pagination.component';
import { ToastService } from '../shared/toast.service';
import { readPageSize, writePageSize } from '../shared/page-size.util';

/**
 * ADMIN-only Order Cancellation page: find an order and cancel it with a
 * mandatory note — even after a courier tracking id (AWB) has been generated
 * (the real-world cases: payment never arrived, or the customer cancels after a
 * partial payment). For a QuikShipX order the cancellation is also sent to the
 * courier so they do NOT send someone to pick the parcel up.
 *
 * <p>Kept as a separate, focused section (not the main Orders table) so the
 * destructive cancel action is deliberate and well-signposted. Only pre-delivery
 * orders are cancellable here; a delivered order is a Return, not a Cancel.
 */
@Component({
  selector: 'admin-order-cancellation',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    CurrencyPipe,
    PageHeaderComponent,
    StatePanelComponent,
    StatusBadgeComponent,
    ChannelLogoComponent,
    PaginationComponent,
  ],
  templateUrl: './order-cancellation.component.html',
  styleUrl: './order-cancellation.component.css',
})
export class OrderCancellationComponent implements OnInit {
  private readonly service = inject(OrdersService);
  private readonly toasts = inject(ToastService);
  private readonly route = inject(ActivatedRoute);

  /**
   * The pre-delivery statuses an order can be cancelled from (mirrors the
   * backend state machine's CANCELLED edges). A delivered/closed/returned order
   * is deliberately excluded — it is a Return, not a Cancel.
   */
  private static readonly CANCELLABLE = new Set<string>([
    OrderStatus.PENDING_ADMIN_APPROVAL,
    OrderStatus.APPROVED,
    OrderStatus.LABEL_GENERATED,
    OrderStatus.PACKED,
    OrderStatus.HANDED_TO_DELIVERY,
    OrderStatus.COURIER_ASSIGNED,
    OrderStatus.DISPATCHED,
    OrderStatus.IN_TRANSIT,
    OrderStatus.OUT_FOR_DELIVERY,
  ]);

  protected readonly humanize = humanizeStatus;

  protected readonly orders = signal<OrderSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected searchTerm = '';

  // Paging (client-side slice over the loaded page).
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('orderCancellation', 10));
  protected readonly totalElements = computed(() => this.orders().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed(() => {
    const start = this.page() * this.size();
    return this.orders().slice(start, start + this.size());
  });

  // --- Cancel modal -------------------------------------------------------
  protected readonly target = signal<OrderSummary | null>(null);
  protected readonly cancelNote = signal('');
  protected readonly cancelling = signal(false);

  ngOnInit(): void {
    // Deep link from the order detail drawer: ?q=<orderCode> pre-fills the search.
    const q = this.route.snapshot.queryParamMap.get('q');
    if (q) {
      this.searchTerm = q;
    }
    // Prime with the most recent orders (or the deep-linked order) so the admin
    // can act without searching.
    this.search();
  }

  /** Whether an order is in a state that can still be cancelled. */
  protected isCancellable(order: OrderSummary): boolean {
    return OrderCancellationComponent.CANCELLABLE.has(order.orderStatus);
  }

  /** Whether this is a QuikShipX order that already has a courier tracking id. */
  protected hasCourierShipment(order: OrderSummary): boolean {
    return order.deliveryMethod !== 'IN_HOUSE' && !!order.quikShipXAwb;
  }

  search(): void {
    this.loading.set(true);
    this.error.set(null);
    this.page.set(0);
    this.service
      .page({ q: this.searchTerm?.trim() || null, page: 0, size: 100, sort: 'createdAt,desc' })
      .subscribe({
        next: (res) => {
          // Show only cancellable (pre-delivery) orders — a delivered/returned
          // order can't be cancelled here.
          this.orders.set(res.content.filter((o) => this.isCancellable(o)));
          this.loading.set(false);
          this.searched.set(true);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load orders. Please try again.');
        },
      });
  }

  clearSearch(): void {
    this.searchTerm = '';
    this.search();
  }

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('orderCancellation', s);
    this.page.set(0);
  }

  // --- Cancel flow --------------------------------------------------------

  openCancel(order: OrderSummary): void {
    this.target.set(order);
    this.cancelNote.set('');
  }

  closeCancel(): void {
    if (this.cancelling()) {
      return;
    }
    this.target.set(null);
    this.cancelNote.set('');
  }

  confirmCancel(): void {
    const order = this.target();
    const note = this.cancelNote().trim();
    if (!order || !note) {
      return;
    }
    this.cancelling.set(true);
    this.service.cancel(order.id, note).subscribe({
      next: (res) => {
        this.cancelling.set(false);
        this.target.set(null);
        // Drop the cancelled order from the list.
        this.orders.update((list) => list.filter((o) => o.id !== order.id));
        if (res.courierCancelAttempted && !res.courierCancelAccepted) {
          this.toasts.error(
            `Order ${order.orderCode} cancelled, but QuikShipX did not confirm the pickup was stopped. ` +
              `Please contact the courier to abort the pickup. (${res.courierMessage ?? 'no detail'})`,
          );
        } else if (res.courierCancelAttempted) {
          this.toasts.success(`Order ${order.orderCode} cancelled and the courier pickup was aborted.`);
        } else {
          this.toasts.success(`Order ${order.orderCode} cancelled.`);
        }
      },
      error: (err) => {
        this.cancelling.set(false);
        const msg = err?.error?.message || 'Could not cancel the order. Please try again.';
        this.toasts.error(msg);
      },
    });
  }
}
