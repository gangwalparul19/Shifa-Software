import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { CartService } from '../shared/cart.service';
import { CartItem, MAX_QUANTITY, MIN_QUANTITY } from '../shared/cart-item.model';
import { formatInr, paiseToMoney, toPaise } from '../shared/money';
import { WhatsAppService } from '../shared/whatsapp.service';

/**
 * Cart page (route 'cart', Req 2.2, 2.3, 3.2): lists line items with an image,
 * name, unit price, a 1..999 quantity stepper, line total and remove control;
 * shows a live subtotal and a "Proceed to Checkout" button; and renders an
 * empty-cart state with a shop CTA. Invalid quantities are rejected and surface
 * a message without changing the cart (Req 2.6).
 */
@Component({
  selector: 'sf-cart',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './cart.component.html',
  styleUrl: './cart.component.css',
})
export class CartComponent {
  protected readonly cart = inject(CartService);
  private readonly whatsapp = inject(WhatsAppService);

  protected readonly MIN = MIN_QUANTITY;
  protected readonly MAX = MAX_QUANTITY;
  protected readonly error = signal<string | null>(null);

  /** wa.me deep link prefilled with the current cart contents. */
  whatsappHref(): string {
    return this.whatsapp.cartLink(this.cart.items());
  }

  lineTotal(item: CartItem): string {
    return formatInr(paiseToMoney(toPaise(item.salePrice) * item.quantity));
  }

  unitPrice(item: CartItem): string {
    return formatInr(item.salePrice);
  }

  subtotalDisplay(): string {
    return formatInr(this.cart.subtotal());
  }

  step(item: CartItem, delta: number): void {
    this.apply(item.productId, item.quantity + delta);
  }

  onQuantityInput(item: CartItem, value: string): void {
    this.apply(item.productId, Number(value));
  }

  remove(productId: number): void {
    this.error.set(null);
    this.cart.remove(productId);
  }

  private apply(productId: number, quantity: number): void {
    const result = this.cart.updateQuantity(productId, quantity);
    this.error.set(result.ok ? null : (result.message ?? 'Invalid quantity.'));
  }
}
