import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Category, Product } from 'core';
import { CatalogService } from '../catalog/catalog.service';
import { ProductCardComponent } from '../shared/product-card.component';
import { SeoService } from '../shared/seo.service';

/** Emoji accent per category slug for the "shop by category" strip. */
const CATEGORY_ICONS: Record<string, string> = {
  immunity: '🌿',
  digestion: '🍵',
  'hair-and-skin': '💆',
  juices: '🥤',
  churna: '🌾',
  'personal-care': '🧴',
};

/**
 * Storefront home page (route ''): hero banner, shop-by-category strip, a live
 * "Bestsellers" grid pulled from the public catalog, a "Why Shifa" value strip,
 * a newsletter subscribe band and testimonials. Featured products come from
 * GET /api/catalog/products (Req 1.1); the rest is curated static content.
 */
@Component({
  selector: 'sf-home',
  imports: [RouterLink, TranslatePipe, ProductCardComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent {
  private readonly catalog = inject(CatalogService);
  private readonly seo = inject(SeoService);

  protected readonly loading = signal(true);
  protected readonly errored = signal(false);
  private readonly products = signal<Product[]>([]);
  private readonly featuredProducts = signal<Product[]>([]);
  protected readonly categories = signal<Category[]>([]);

  /** Show up to 8 products as bestsellers. */
  protected readonly bestsellers = computed(() => this.products().slice(0, 8));

  /** Featured collection (featured=true), capped for the home row. */
  protected readonly featured = computed(() => this.featuredProducts().slice(0, 8));

  iconFor(slug: string): string {
    return CATEGORY_ICONS[slug] ?? '🌿';
  }

  protected readonly values = [
    { icon: '🌱', title: '100% Natural', blurb: 'No parabens, sulphates or artificial colour — ever.' },
    { icon: '📜', title: 'Authentic Ayurveda', blurb: 'Time-honoured formulations, made in small batches.' },
    { icon: '🔬', title: 'Quality Tested', blurb: 'Every batch checked for purity and potency.' },
    { icon: '🚚', title: 'Pan-India Delivery', blurb: 'Fast, tracked shipping to your doorstep.' },
  ];

  protected readonly testimonials = [
    { quote: 'The Ashwagandha capsules genuinely improved my sleep within a fortnight.', name: 'Priya S.', place: 'Pune' },
    { quote: 'Brahmi hair oil is now a staple in our home. Noticeably less hair fall.', name: 'Rahul M.', place: 'Delhi' },
    { quote: 'Authentic products, honest pricing and quick delivery. Highly recommend.', name: 'Anjali K.', place: 'Bengaluru' },
  ];

  constructor() {
    this.seo.setPage({
      title: 'Shifa Herbal Remedies — Pure Ayurveda, Naturally',
      brand: true,
      description:
        'Shop authentic Ayurvedic and herbal wellness products from Shifa. ' +
        '100% natural, ethically sourced and delivered across India.',
    });

    this.catalog.list().subscribe({
      next: (items) => {
        this.products.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.errored.set(true);
      },
    });

    this.catalog.search({ featured: true }).subscribe({
      next: (items) => this.featuredProducts.set(items),
      error: () => this.featuredProducts.set([]),
    });

    this.catalog.categories().subscribe({
      next: (items) => this.categories.set(items),
      error: () => this.categories.set([]),
    });
  }
}
