import {
  Component,
  ElementRef,
  HostListener,
  computed,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { CartService } from '../shared/cart.service';
import { CartDrawerService } from '../shared/cart-drawer.service';
import { CartItem, MAX_QUANTITY, MIN_QUANTITY } from '../shared/cart-item.model';
import { formatInr, paiseToMoney, toPaise } from '../shared/money';
import { FREE_SHIPPING_THRESHOLD } from '../shared/shipping-info';

/**
 * Slide-in cart drawer (Phase 1.3 UI/UX). Toggled from the header cart button,
 * it complements — but does not replace — the full {@code /cart} page.
 *
 * <p>Shows line items with a 1..999 quantity stepper and remove control, a live
 * subtotal, a "you're ₹X away from FREE shipping" progress bar (threshold
 * {@link FREE_SHIPPING_THRESHOLD}), and CTAs to view the cart or check out. It's
 * a focus-managed dialog: {@code aria-modal}, initial focus on the close button,
 * focus trapped within, closes on {@code ESC} or backdrop click, and restores
 * focus to the trigger. The slide animation is suppressed under
 * {@code prefers-reduced-motion} (handled in CSS).
 */
@Component({
  selector: 'sf-cart-drawer',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './cart-drawer.component.html',
  styleUrl: './cart-drawer.component.css',
})
export class CartDrawerComponent {
  protected readonly cart = inject(CartService);
  protected readonly drawer = inject(CartDrawerService);

  protected readonly MIN = MIN_QUANTITY;
  protected readonly MAX = MAX_QUANTITY;

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly closeBtn = viewChild<ElementRef<HTMLButtonElement>>('closeBtn');
  /** The element focused before opening, restored on close. */
  private lastFocused: HTMLElement | null = null;

  /** Free-shipping threshold formatted for display (e.g. "₹499"). */
  protected readonly freeShippingThreshold = formatInr(FREE_SHIPPING_THRESHOLD);

  /** Remaining amount (formatted) until free shipping unlocks; '' once reached. */
  protected readonly amountToFreeShipping = computed(() => {
    const remaining = FREE_SHIPPING_THRESHOLD * 100 - toPaise(this.cart.subtotal());
    return remaining > 0 ? formatInr(paiseToMoney(remaining)) : '';
  });

  /** Progress toward free shipping as a 0..100 percentage for the bar. */
  protected readonly freeShippingPercent = computed(() => {
    const subtotalPaise = toPaise(this.cart.subtotal());
    const thresholdPaise = FREE_SHIPPING_THRESHOLD * 100;
    if (thresholdPaise <= 0) {
      return 100;
    }
    return Math.min(100, Math.round((subtotalPaise / thresholdPaise) * 100));
  });

  /** True once the cart qualifies for free shipping. */
  protected readonly freeShippingReached = computed(
    () => toPaise(this.cart.subtotal()) >= FREE_SHIPPING_THRESHOLD * 100,
  );

  protected readonly subtotalDisplay = computed(() => formatInr(this.cart.subtotal()));

  constructor() {
    // Manage focus + body scroll lock as the drawer opens/closes.
    effect(() => {
      if (typeof document === 'undefined') {
        return;
      }
      if (this.drawer.open()) {
        this.lastFocused = document.activeElement as HTMLElement | null;
        document.body.style.overflow = 'hidden';
        queueMicrotask(() => this.closeBtn()?.nativeElement.focus());
      } else {
        document.body.style.overflow = '';
        this.lastFocused?.focus?.();
        this.lastFocused = null;
      }
    });
  }

  lineTotal(item: CartItem): string {
    return formatInr(paiseToMoney(toPaise(item.salePrice) * item.quantity));
  }

  unitPrice(item: CartItem): string {
    return formatInr(item.salePrice);
  }

  step(item: CartItem, delta: number): void {
    this.cart.updateQuantity(item.productId, item.quantity + delta);
  }

  remove(productId: number): void {
    this.cart.remove(productId);
  }

  close(): void {
    this.drawer.close();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.drawer.open()) {
      this.close();
    }
  }

  /** Simple focus trap: keep Tab cycling within the open panel. */
  @HostListener('document:keydown.tab', ['$event'])
  @HostListener('document:keydown.shift.tab', ['$event'])
  onTab(event: Event): void {
    if (!this.drawer.open() || !(event instanceof KeyboardEvent)) {
      return;
    }
    const root = this.panel()?.nativeElement;
    if (!root) {
      return;
    }
    const focusable = Array.from(
      root.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ).filter((el) => el.offsetParent !== null);
    if (focusable.length === 0) {
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement as HTMLElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}
