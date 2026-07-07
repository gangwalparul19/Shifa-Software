import { DatePipe } from '@angular/common';
import { Component, ElementRef, OnDestroy, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { WhatsAppService } from '../shared/whatsapp.service';
import {
  AuthService,
  Product,
  ProductReviews,
  isPurchasable,
  stockBadgeLabel,
  stockBadgeTone,
} from 'core';
import { SeoService } from '../shared/seo.service';
import { slugify } from '../shared/slug';
import { deliveryEstimate } from '../shared/shipping-info';
import { CatalogService } from './catalog.service';
import { ReviewsService } from './reviews.service';
import { CartService } from '../shared/cart.service';
import { WishlistService } from '../shared/wishlist.service';
import { ToastService } from '../shared/toast.service';
import { FlyToCartService } from '../shared/fly-to-cart.service';
import { ProductCardComponent } from '../shared/product-card.component';
import { StarRatingComponent } from '../shared/star-rating.component';
import { MAX_QUANTITY, MIN_QUANTITY } from '../shared/cart-item.model';
import { discountPercent, formatInr } from '../shared/money';
import { productPrimaryImage } from '../shared/product-image';

/**
 * Storefront product detail view (Req 1.2, 1.4, 1.7 + Phase C reviews).
 *
 * <p>Loads a single published product by id and shows a large image, name, SKU,
 * an average star rating + review count near the title, a price block, a
 * quantity selector, add-to-cart and wishlist, a description, a "You may also
 * like" row, and a Reviews section: an average summary + per-star breakdown, the
 * list of approved reviews, and a "Write a review" form for logged-in customers
 * (a login prompt otherwise). A 404 renders an "unavailable" state (Req 1.7).
 */
@Component({
  selector: 'sf-product-detail',
  imports: [
    DatePipe,
    RouterLink,
    ReactiveFormsModule,
    TranslatePipe,
    ProductCardComponent,
    StarRatingComponent,
  ],
  templateUrl: './product-detail.component.html',
  styleUrl: './product-detail.component.css',
})
export class ProductDetailComponent implements OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly catalog = inject(CatalogService);
  private readonly reviewsApi = inject(ReviewsService);
  private readonly cart = inject(CartService);
  private readonly wishlist = inject(WishlistService);
  private readonly toast = inject(ToastService);
  private readonly flyToCart = inject(FlyToCartService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly whatsapp = inject(WhatsAppService);
  private readonly seo = inject(SeoService);
  private readonly translate = inject(TranslateService);

  private readonly galleryImg = viewChild<ElementRef<HTMLImageElement>>('galleryImg');
  /** Drives the brief wishlist-heart "pop" animation on toggle. */
  protected readonly heartPop = signal(false);

  protected readonly MIN = MIN_QUANTITY;
  protected readonly MAX = MAX_QUANTITY;
  protected readonly stars = [1, 2, 3, 4, 5];

  /** Static pan-India delivery estimate shown near add-to-cart. */
  protected readonly deliveryEstimate = deliveryEstimate();

  protected readonly product = signal<Product | null>(null);
  protected readonly loading = signal(true);
  protected readonly unavailable = signal(false);
  protected readonly quantity = signal(1);
  protected readonly added = signal(false);
  protected readonly related = signal<Product[]>([]);

  // --- Reviews state ------------------------------------------------------
  protected readonly reviews = signal<ProductReviews | null>(null);
  protected readonly reviewsLoading = signal(false);
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly formRating = signal(0);

  protected readonly reviewForm = this.fb.nonNullable.group({
    title: ['', [Validators.maxLength(150)]],
    body: ['', [Validators.maxLength(2000)]],
  });

  protected readonly isLoggedIn = computed(() => this.auth.isAuthenticated());

  protected readonly image = computed(() => {
    const p = this.product();
    return p ? productPrimaryImage(p) : '';
  });
  protected readonly salePrice = computed(() => formatInr(this.product()?.salePrice));
  protected readonly mrp = computed(() => formatInr(this.product()?.mrp));
  protected readonly discount = computed(() =>
    discountPercent(this.product()?.mrp, this.product()?.salePrice),
  );
  protected readonly saved = computed(() => {
    const p = this.product();
    return p ? this.wishlist.has(p.id) : false;
  });
  protected readonly purchasable = computed(() => {
    const p = this.product();
    return p ? isPurchasable(p) : false;
  });
  protected readonly stockLabel = computed(() => {
    const p = this.product();
    return p ? stockBadgeLabel(p) : '';
  });
  protected readonly stockTone = computed(() => {
    const p = this.product();
    return p ? stockBadgeTone(p) : 'ok';
  });

  /** Average rating for the header — prefers the freshly loaded reviews aggregate. */
  protected readonly averageRating = computed(
    () => this.reviews()?.averageRating ?? this.product()?.averageRating ?? null,
  );
  protected readonly reviewCount = computed(
    () => this.reviews()?.reviewCount ?? this.product()?.reviewCount ?? 0,
  );

  /** Per-star breakdown rows (5→1) with count + percentage for the histogram. */
  protected readonly breakdownRows = computed(() => {
    const data = this.reviews();
    const total = data?.reviewCount ?? 0;
    return [5, 4, 3, 2, 1].map((star) => {
      const count = data?.breakdown?.[String(star)] ?? 0;
      return { star, count, percent: total > 0 ? Math.round((count / total) * 100) : 0 };
    });
  });

  constructor() {
    // React to param changes so "you may also like" navigation reloads cleanly.
    this.route.paramMap.subscribe((params) => {
      const id = Number(params.get('id'));
      this.reset();
      if (!Number.isFinite(id) || id <= 0) {
        this.loading.set(false);
        this.unavailable.set(true);
        return;
      }
      this.catalog.detail(id).subscribe({
        next: (product) => {
          this.product.set(product);
          this.loading.set(false);
          this.applySeo(product);
          this.loadRelated(id);
          this.loadReviews(id);
        },
        error: () => {
          this.loading.set(false);
          this.unavailable.set(true);
          this.seo.setPage({
            title: 'Product unavailable',
            description: 'This product is no longer available.',
          });
        },
      });
    });
  }

  ngOnDestroy(): void {
    // Don't leave stale product structured data in the head after navigating away.
    this.seo.clearJsonLd();
  }

  /**
   * Sets the page title/description + Open Graph tags for the product and injects
   * a schema.org {@code Product}/{@code Offer} JSON-LD block for rich results.
   */
  private applySeo(product: Product): void {
    const image = productPrimaryImage(product);
    const description =
      product.description?.trim() ||
      `${product.name} — authentic Ayurvedic wellness from Shifa Herbal Remedies.`;
    this.seo.setPage({
      title: product.name,
      description,
      image,
      type: 'product',
      url: this.seoUrl(product),
    });
    this.seo.setJsonLd(this.buildJsonLd(product, image, description));
  }

  /** Canonical, slugged product URL for social/SEO tags (absolute when possible). */
  private seoUrl(product: Product): string | undefined {
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const slug = slugify(product.name);
    const path = slug ? `/products/${product.id}/${slug}` : `/products/${product.id}`;
    return origin ? `${origin}${path}` : undefined;
  }

  /** Builds a schema.org Product + Offer structured-data object. */
  private buildJsonLd(
    product: Product,
    image: string,
    description: string,
  ): Record<string, unknown> {
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const absoluteImage = image && origin && image.startsWith('/') ? `${origin}${image}` : image;
    const availability = isPurchasable(product)
      ? 'https://schema.org/InStock'
      : 'https://schema.org/OutOfStock';
    const jsonLd: Record<string, unknown> = {
      '@context': 'https://schema.org',
      '@type': 'Product',
      name: product.name,
      sku: product.sku,
      description,
      image: absoluteImage || undefined,
      brand: { '@type': 'Brand', name: 'Shifa Herbal Remedies' },
      offers: {
        '@type': 'Offer',
        priceCurrency: 'INR',
        price: product.salePrice,
        availability,
        url: this.seoUrl(product),
      },
    };
    if (product.category) {
      jsonLd['category'] = product.category.name;
    }
    if (product.averageRating != null && (product.reviewCount ?? 0) > 0) {
      jsonLd['aggregateRating'] = {
        '@type': 'AggregateRating',
        ratingValue: product.averageRating,
        reviewCount: product.reviewCount,
      };
    }
    return jsonLd;
  }

  changeQuantity(delta: number): void {
    const next = this.quantity() + delta;
    if (next >= this.MIN && next <= this.MAX) {
      this.quantity.set(next);
    }
  }

  setQuantity(value: string): void {
    const parsed = Number(value);
    if (Number.isInteger(parsed) && parsed >= this.MIN && parsed <= this.MAX) {
      this.quantity.set(parsed);
    }
  }

  addToCart(): void {
    const p = this.product();
    if (!p || !this.purchasable()) {
      return;
    }
    const result = this.cart.add(p, this.quantity());
    if (result.ok) {
      this.added.set(true);
      setTimeout(() => this.added.set(false), 2000);
      this.flyToCart.fly(this.galleryImg()?.nativeElement, this.image());
      this.toast.success(this.t('toast.addedToCart'));
    } else if (result.message) {
      this.toast.error(result.message);
    }
  }

  toggleWishlist(): void {
    const p = this.product();
    if (!p) {
      return;
    }
    const saved = this.wishlist.toggle(p);
    this.triggerHeartPop();
    this.toast.success(this.t(saved ? 'toast.savedToWishlist' : 'toast.removedFromWishlist'));
  }

  /** Briefly toggles the heart "pop" class (CSS honours reduced-motion). */
  private triggerHeartPop(): void {
    this.heartPop.set(false);
    requestAnimationFrame(() => {
      this.heartPop.set(true);
      setTimeout(() => this.heartPop.set(false), 400);
    });
  }

  private t(key: string): string {
    return this.translate.instant(key) as string;
  }

  /** wa.me deep link prefilled with an enquiry about this product. */
  whatsappHref(): string {
    const p = this.product();
    if (!p) {
      return this.whatsapp.genericLink();
    }
    return this.whatsapp.productLink(p.name, formatInr(p.salePrice));
  }

  // --- Reviews ------------------------------------------------------------

  setRating(star: number): void {
    this.formRating.set(star);
    this.submitError.set(null);
  }

  submitReview(): void {
    const p = this.product();
    if (!p || this.submitting()) {
      return;
    }
    const rating = this.formRating();
    if (rating < 1 || rating > 5) {
      this.submitError.set('Please select a star rating.');
      return;
    }
    const { title, body } = this.reviewForm.getRawValue();
    this.submitting.set(true);
    this.submitError.set(null);
    this.reviewsApi
      .submit({
        productId: p.id,
        rating,
        title: title.trim() || undefined,
        body: body.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.submitted.set(true);
          this.reviewForm.reset({ title: '', body: '' });
          this.formRating.set(0);
        },
        error: () => {
          this.submitting.set(false);
          this.submitError.set('Could not submit your review. Please try again.');
        },
      });
  }

  private loadReviews(id: number): void {
    this.reviewsLoading.set(true);
    this.reviewsApi.forProduct(id).subscribe({
      next: (data) => {
        this.reviews.set(data);
        this.reviewsLoading.set(false);
      },
      error: () => {
        this.reviews.set(null);
        this.reviewsLoading.set(false);
      },
    });
  }

  private loadRelated(currentId: number): void {
    this.catalog.related(currentId).subscribe({
      next: (items) => this.related.set(items),
      error: () => this.related.set([]),
    });
  }

  private reset(): void {
    this.loading.set(true);
    this.unavailable.set(false);
    this.product.set(null);
    this.quantity.set(1);
    this.added.set(false);
    this.related.set([]);
    this.reviews.set(null);
    this.reviewsLoading.set(false);
    this.submitting.set(false);
    this.submitted.set(false);
    this.submitError.set(null);
    this.formRating.set(0);
    this.reviewForm.reset({ title: '', body: '' });
  }
}
