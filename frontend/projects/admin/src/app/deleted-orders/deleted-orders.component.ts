import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';

import { OrdersService } from '../orders/orders.service';
import { OrderSummary } from '../orders/orders.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { InrPipe } from '../shared/inr.pipe';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { humanizeStatus } from '../shared/status-badge.component';

/**
 * Admin "Deleted orders" view (delete-order feature): lists the soft-deleted
 * (inactive) orders — the ones hidden everywhere else by the {@code active}
 * flag — and lets an admin restore any of them. Backed by
 * {@code GET /api/admin/orders/deleted} and {@code POST /api/admin/orders/{id}/restore}
 * (both ADMIN-only). Restoring reactivates the order so it reappears across the
 * whole app (lists, dashboards, reports, P&L).
 */
@Component({
  selector: 'admin-deleted-orders',
  imports: [RouterLink, IstDatePipe, InrPipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './deleted-orders.component.html',
  styleUrl: './deleted-orders.component.css',
})
export class DeletedOrdersComponent implements OnInit {
  private readonly service = inject(OrdersService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly orders = signal<OrderSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** Id currently being restored (disables its button + shows a spinner). */
  protected readonly restoringId = signal<number | null>(null);

  protected readonly humanize = humanizeStatus;
  protected readonly count = computed(() => this.orders().length);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.deletedOrders().subscribe({
      next: (list) => {
        this.orders.set(list ?? []);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loadError.set(
          error.status === 403
            ? 'Only administrators can view deleted orders.'
            : 'Could not load deleted orders. Please try again.',
        );
        this.loading.set(false);
      },
    });
  }

  async restore(order: OrderSummary): Promise<void> {
    if (this.restoringId() !== null) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Restore order',
      message:
        `Restore order ${order.orderCode}? It will become active again and reappear ` +
        `in the orders list, dashboards, reports and the P&L.`,
      confirmLabel: 'Restore',
      icon: 'ti-restore',
    });
    if (!confirmed) {
      return;
    }
    this.restoringId.set(order.id);
    this.service.restoreOrder(order.id).subscribe({
      next: () => {
        this.restoringId.set(null);
        this.toasts.success(`Order ${order.orderCode} restored.`);
        // Drop it from the deleted list (it's active again now).
        this.orders.set(this.orders().filter((o) => o.id !== order.id));
      },
      error: () => {
        this.restoringId.set(null);
        this.toasts.error('Could not restore the order. Please try again.');
      },
    });
  }
}
