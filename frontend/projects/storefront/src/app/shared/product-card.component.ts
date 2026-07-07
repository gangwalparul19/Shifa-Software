import { Component, ElementRef, computed, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { Product, isPurchasable, stockBadgeLabel, stockBadgeTone } from 'core';
import { CartService } from './cart.service';
import { WishlistService } from './wishlist.service';
import { ToastService } from './toast.service';
import { FlyToCartService } from './fly-to-cart.service';
import { StarRatingComponent } from './star-rating.component';
import { discountPercent, formatInr } from './money';
import { productPrimaryImage } from './product-image';
import { productLink } from './slug';

/**
 * Reusable catalog product card: image, name, price block (MRP struck-through +
 * sale price + % off), an add-to-cart button and a wishlist heart. Used by the
 * home, shop and (indirectly) wishlist views for a consistent look.
 */
@Component({
  selector: 'sf-product-card',
  imports: [RouterLink, StarRatingComponent],
  template: `
    <article class="card">
      <a class="media" [routerLink]="link()">
        <img #cardImg [src]="image()" [alt]="product().name" loading="lazy" />
        @if (discount() > 0) {
          <span class="pill pill-sale">-{{ discount() }}%</span>
        }
        <span class="stock-badge" [attr.data-tone]="stockTone()">{{ stockLabel() }}</span>
      </a>
      <button
        type="button"
        class="wish"
        [class.active]="saved()"
        [class.pop]="heartPop()"
        (click)="toggleWishlist()"
        [attr.aria-label]="saved() ? 'Remove from wishlist' : 'Add to wishlist'"
      >
        {{ saved() ? '♥' : '♡' }}
      </button>

      <div class="body">
        <a class="name" [routerLink]="link()">{{ product().name }}</a>
        @if (reviewCount() > 0) {
          <span class="card-rating">
            <sf-star-rating [rating]="averageRating()" [count]="reviewCount()" />
          </span>
        }
        <div class="foot">
          <div class="prices">
            <span class="sale">{{ salePrice() }}</span>
            @if (discount() > 0) {
              <span class="strike">{{ mrp() }}</span>
            }
          </div>
          @if (purchasable()) {
            <button
              type="button"
              class="cart-btn"
              [class.added]="inCart()"
              (click)="addToCart()"
              [attr.aria-label]="inCart() ? 'Added to cart' : 'Add to cart'"
              [title]="inCart() ? 'Added to cart' : 'Add to cart'"
            >
              @if (inCart()) {
                <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
                  <path
                    d="M5 13l4 4L19 7"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2.4"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              } @else {
                <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
                  <path
                    d="M4 4h2l1.2 2M7.2 6h12l-1.5 7.5a1.5 1.5 0 0 1-1.5 1.2H9a1.5 1.5 0 0 1-1.5-1.2L6 3.5"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                  <circle cx="9.5" cy="19" r="1.4" fill="currentColor" />
                  <circle cx="16.5" cy="19" r="1.4" fill="currentColor" />
                </svg>
              }
            </button>
          } @else {
            <span class="oos-tag">Out of stock</span>
          }
        </div>
      </div>
    </article>
  `,
  styleUrl: './product-card.component.css',
})
export class ProductCardComponent {
  readonly product = input.required<Product>();

  private readonly cart = inject(CartService);
  private readonly wishlist = inject(WishlistService);
  private readonly toast = inject(ToastService);
  private readonly flyToCart = inject(FlyToCartService);
  private readonly translate = inject(TranslateService);

  private readonly cardImg = viewChild<ElementRef<HTMLImageElement>>('cardImg');
  /** Drives the brief wishlist-heart "pop" animation on toggle. */
  protected readonly heartPop = signal(false);

  /** SEO-friendly product link commands ({@code /products/:id/:slug}). */
  protected readonly link = computed(() => productLink(this.product().id, this.product().name));
  protected readonly image = computed(() => productPrimaryImage(this.product()));
  protected readonly salePrice = computed(() => formatInr(this.product().salePrice));
  protected readonly mrp = computed(() => formatInr(this.product().mrp));
  protected readonly discount = computed(() =>
    discountPercent(this.product().mrp, this.product().salePrice),
  );
  protected readonly averageRating = computed(() => this.product().averageRating ?? null);
  protected readonly reviewCount = computed(() => this.product().reviewCount ?? 0);
  protected readonly saved = computed(() => this.wishlist.has(this.product().id));
  protected readonly inCart = computed(() => this.cart.has(this.product().id));
  protected readonly purchasable = computed(() => isPurchasable(this.product()));
  protected readonly stockLabel = computed(() => stockBadgeLabel(this.product()));
  protected readonly stockTone = computed(() => stockBadgeTone(this.product()));

  addToCart(): void {
    if (!this.purchasable()) {
      return;
    }
    const result = this.cart.add(this.product(), 1);
    if (result.ok) {
      this.flyToCart.fly(this.cardImg()?.nativeElement, this.image());
      this.toast.success(this.t('toast.addedToCart'));
    } else if (result.message) {
      this.toast.error(result.message);
    }
  }

  toggleWishlist(): void {
    const saved = this.wishlist.toggle(this.product());
    this.triggerHeartPop();
    this.toast.success(this.t(saved ? 'toast.savedToWishlist' : 'toast.removedFromWishlist'));
  }

  /** Briefly toggles the heart "pop" class (CSS honours reduced-motion). */
  private triggerHeartPop(): void {
    this.heartPop.set(false);
    // Re-add on the next frame so the animation restarts on rapid toggles.
    requestAnimationFrame(() => {
      this.heartPop.set(true);
      setTimeout(() => this.heartPop.set(false), 400);
    });
  }

  private t(key: string): string {
    return this.translate.instant(key) as string;
  }
}
