import { Component, Input } from '@angular/core';

/**
 * Small logo chips that let staff instantly tell an order's SOURCE and COURIER
 * apart on the portal:
 *  - a Shopify logo when the order was imported from Shopify;
 *  - a QuikShipX logo when the order is (or will be) shipped via QuikShipX.
 *
 * Pass whichever signals a view has. For an order row:
 *   <admin-channel-logo [source]="order.source" [quikShip]="!!order.quikShipXStatus" />
 *
 * The logos live in the admin public assets ({@code /shopify-logo.png},
 * {@code /quickship-logo.png}) alongside the brand {@code /logo.png}; the PWA
 * service worker caches them via the existing {@code /*.png} asset glob.
 */
@Component({
  selector: 'admin-channel-logo',
  standalone: true,
  template: `
    @if (isShopify) {
      <span class="ch-logo ch-logo--shopify" [title]="shopifyTitle">
        <img src="/shopify-logo.png" alt="Shopify" />
        @if (label) { <span class="ch-logo__text">Shopify</span> }
      </span>
    }
    @if (quikShip) {
      <span class="ch-logo ch-logo--quikship" [title]="quikShipTitle">
        <img src="/quickship-logo.png" alt="QuikShipX" />
        @if (label) { <span class="ch-logo__text">QuikShipX</span> }
      </span>
    }
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        vertical-align: middle;
      }
      .ch-logo {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        padding: 0.15rem 0.4rem;
        border-radius: 6px;
        background: #fff;
        border: 1px solid var(--tblr-border-color, #e6e7e9);
        line-height: 1;
      }
      .ch-logo img {
        display: block;
        width: auto;
        object-fit: contain;
      }
      /* Default (sm) sizing */
      .ch-logo img {
        height: 16px;
        max-width: 70px;
      }
      :host(.ch-md) .ch-logo img {
        height: 22px;
        max-width: 96px;
      }
      :host(.ch-lg) .ch-logo img {
        height: 30px;
        max-width: 128px;
      }
      .ch-logo__text {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--tblr-secondary, #667085);
      }
      .ch-logo--shopify {
        border-color: #c9e8d4;
        background: #f4fbf6;
      }
      .ch-logo--quikship {
        border-color: #cfe0f5;
        background: #f5f9ff;
      }
    `,
  ],
})
export class ChannelLogoComponent {
  /** The order source; renders the Shopify logo when 'SHOPIFY'. */
  @Input() source: string | null | undefined;
  /** True when the order is handled by QuikShipX (renders the QuikShipX logo). */
  @Input() quikShip = false;
  /** Show the text label beside each logo (default: logo only). */
  @Input() label = false;

  protected readonly shopifyTitle = 'Imported from Shopify';
  protected readonly quikShipTitle = 'Shipped via QuikShipX';

  /** Whether the order source is Shopify (case-insensitive). */
  protected get isShopify(): boolean {
    return (this.source ?? '').toUpperCase() === 'SHOPIFY';
  }
}
