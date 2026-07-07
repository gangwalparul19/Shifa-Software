import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ProductVisibility } from 'core';
import { CartService } from '../shared/cart.service';
import { formatInr } from '../shared/money';
import { AccountApi, AccountOrderSummary } from './account-api.service';

/**
 * "My Orders" — the signed-in customer's order history (Phase B). Lists their
 * orders with status, links to public tracking, offers an invoice download
 * (public invoice endpoint), and a one-tap reorder that re-adds the order's
 * items to the cart.
 */
@Component({
  selector: 'sf-account-orders',
  imports: [RouterLink, DatePipe],
  templateUrl: './account-orders.component.html',
  styleUrl: './account.css',
})
export class AccountOrdersComponent {
  private readonly accountApi = inject(AccountApi);
  private readonly cart = inject(CartService);

  protected readonly orders = signal<AccountOrderSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly reordering = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.accountApi.listOrders().subscribe({
      next: (orders) => {
        this.orders.set(orders);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('We could not load your orders right now.');
        this.loading.set(false);
      },
    });
  }

  money(value: string): string {
    return formatInr(value);
  }

  invoiceUrl(orderCode: string): string {
    return this.accountApi.invoiceUrl(orderCode);
  }

  /** Re-adds every line of a past order to the cart, then confirms. */
  reorder(orderCode: string): void {
    if (this.reordering()) {
      return;
    }
    this.reordering.set(orderCode);
    this.notice.set(null);
    this.accountApi.getOrder(orderCode).subscribe({
      next: (detail) => {
        for (const line of detail.items) {
          this.cart.add(
            {
              id: line.productId,
              sku: '',
              name: line.productName,
              salePrice: line.rate,
              mrp: line.rate,
              visibility: ProductVisibility.PUBLISHED,
            },
            line.quantity,
          );
        }
        this.reordering.set(null);
        this.notice.set(`Added ${detail.items.length} item(s) from ${orderCode} to your cart.`);
      },
      error: () => {
        this.reordering.set(null);
        this.notice.set('We could not reorder that right now.');
      },
    });
  }
}
