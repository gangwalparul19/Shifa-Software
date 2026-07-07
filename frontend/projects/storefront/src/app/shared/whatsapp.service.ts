import { Injectable, inject } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { CartItem } from './cart-item.model';
import { formatInr, paiseToMoney, toPaise } from './money';
import { StorefrontConfigService } from './storefront-config.service';

/**
 * Builds "Order / Enquire on WhatsApp" deep links (Phase F engagement).
 *
 * <p>Produces {@code https://wa.me/<number>?text=<encoded message>} URLs where
 * the number comes from the public storefront config (settings contact phone,
 * with a configured fallback) and the message is prefilled and URL-encoded:
 *
 * <ul>
 *   <li>{@link cartMessage} — a short intro, one line per cart item
 *       ("name × qty — line total") and the subtotal, for the Cart page;</li>
 *   <li>{@link productMessage} — an intro plus a single product line, for the
 *       Product detail page;</li>
 *   <li>{@link genericMessage} — a plain enquiry when there is no context.</li>
 * </ul>
 *
 * All labels/intro lines are translated via ngx-translate so the handoff text
 * matches the customer's chosen language.
 */
@Injectable({ providedIn: 'root' })
export class WhatsAppService {
  private readonly config = inject(StorefrontConfigService);
  private readonly translate = inject(TranslateService);

  /** Deep link prefilled with the current cart (falls back to a generic enquiry when empty). */
  cartLink(items: readonly CartItem[]): string {
    return this.buildLink(items.length > 0 ? this.cartMessage(items) : this.genericMessage());
  }

  /** Deep link prefilled with a single product enquiry. */
  productLink(name: string, price?: string): string {
    return this.buildLink(this.productMessage(name, price));
  }

  /** Deep link with a generic product enquiry (no cart/product context). */
  genericLink(): string {
    return this.buildLink(this.genericMessage());
  }

  /**
   * Deep link for sharing arbitrary text (e.g. a wishlist link) with any
   * contact. Unlike the enquiry links this omits the store number so WhatsApp
   * lets the sender pick the recipient ({@code https://wa.me/?text=...}).
   */
  shareLink(text: string): string {
    return `https://wa.me/?text=${encodeURIComponent(text)}`;
  }

  /** Builds the multi-line cart message: intro + item lines + subtotal. */
  cartMessage(items: readonly CartItem[]): string {
    const intro = this.t('whatsapp.introCart');
    const lines = items.map((item) =>
      this.t('whatsapp.qtyLine', {
        name: item.name,
        qty: item.quantity,
        amount: formatInr(paiseToMoney(toPaise(item.salePrice) * item.quantity)),
      }),
    );
    const subtotalPaise = items.reduce(
      (sum, item) => sum + toPaise(item.salePrice) * item.quantity,
      0,
    );
    const subtotal = this.t('whatsapp.subtotalLine', {
      amount: formatInr(paiseToMoney(subtotalPaise)),
    });
    return [intro, '', ...lines, '', subtotal].join('\n');
  }

  /** Builds a single-product enquiry message. */
  productMessage(name: string, price?: string): string {
    const intro = this.t('whatsapp.introProduct');
    const line = price ? `• ${name} — ${price}` : `• ${name}`;
    return [intro, '', line].join('\n');
  }

  /** Builds a generic enquiry message. */
  genericMessage(): string {
    return this.t('whatsapp.introGeneric');
  }

  /** Assembles the encoded wa.me URL for a ready message string. */
  private buildLink(message: string): string {
    const number = this.config.whatsappNumber();
    return `https://wa.me/${number}?text=${encodeURIComponent(message)}`;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.translate.instant(key, params) as string;
  }
}
