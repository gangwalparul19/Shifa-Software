import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Category, Product } from 'core';
import { CatalogFilters, CatalogService } from '../catalog/catalog.service';
import { ProductCardComponent } from '../shared/product-card.component';
import { SeoService } from '../shared/seo.service';

type SortOption = NonNullable<CatalogFilters['sort']>;

/**
 * Shop / catalog page (route 'shop'): a responsive product grid over the live
 * published catalog with a filter sidebar (category chips, price range, in-stock
 * toggle) and a sort dropdown, all wired to the catalog endpoint query params
 * (Req 1.1, 1.3 + Catalog & Discovery). Reads {@code ?q=} and {@code ?category=}
 * query params so header searches and category links deep-link into filtered
 * results. The filter panel collapses on mobile.
 */
@Component({
  selector: 'sf-shop',
  imports: [ProductCardComponent, TranslatePipe],
  templateUrl: './shop.component.html',
  styleUrl: './shop.component.css',
})
export class ShopComponent {
  private readonly catalog = inject(CatalogService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly seo = inject(SeoService);

  protected readonly products = signal<Product[]>([]);
  protected readonly categories = signal<Category[]>([]);
  protected readonly loading = signal(true);
  protected readonly errored = signal(false);

  // Filter state.
  protected readonly activeQuery = signal('');
  protected readonly selectedCategory = signal<string | null>(null);
  protected readonly minPrice = signal('');
  protected readonly maxPrice = signal('');
  protected readonly inStockOnly = signal(false);
  protected readonly sort = signal<SortOption>('relevance');
  protected readonly filtersOpen = signal(false);

  protected readonly sortOptions: { value: SortOption; label: string }[] = [
    { value: 'relevance', label: 'Relevance' },
    { value: 'price_asc', label: 'Price: Low to High' },
    { value: 'price_desc', label: 'Price: High to Low' },
    { value: 'name_asc', label: 'Name: A to Z' },
    { value: 'newest', label: 'Newest' },
  ];

  protected readonly activeCategoryName = computed(() => {
    const slug = this.selectedCategory();
    return slug ? this.categories().find((c) => c.slug === slug)?.name ?? null : null;
  });

  protected readonly hasActiveFilters = computed(
    () =>
      this.selectedCategory() !== null ||
      this.minPrice() !== '' ||
      this.maxPrice() !== '' ||
      this.inStockOnly() ||
      this.activeQuery() !== '',
  );

  constructor() {
    this.catalog.categories().subscribe({
      next: (items) => {
        this.categories.set(items);
        this.applySeo();
      },
      error: () => this.categories.set([]),
    });

    // Seed filter state from the URL, then react to later changes.
    this.route.queryParamMap.subscribe((params) => {
      this.activeQuery.set((params.get('q') ?? '').trim());
      this.selectedCategory.set(params.get('category') || null);
      this.applySeo();
      this.load();
    });
  }

  /** Category-/query-aware page metadata for the shop grid. */
  private applySeo(): void {
    const category = this.activeCategoryName();
    const query = this.activeQuery();
    let title = 'Shop';
    let description =
      'Browse the full range of Shifa herbal and Ayurvedic wellness products, delivered across India.';
    if (category) {
      title = `${category} — Shop`;
      description = `Shop ${category} products from Shifa Herbal Remedies. 100% natural, delivered across India.`;
    } else if (query) {
      title = `Search: ${query} — Shop`;
      description = `Search results for "${query}" in the Shifa herbal wellness catalog.`;
    }
    this.seo.setPage({ title, description });
  }

  search(query: string): void {
    this.updateUrl({ q: query.trim() || null });
  }

  selectCategory(slug: string | null): void {
    this.updateUrl({ category: slug });
  }

  setSort(value: string): void {
    this.sort.set(value as SortOption);
    this.load();
  }

  applyPriceRange(min: string, max: string): void {
    this.minPrice.set(min.trim());
    this.maxPrice.set(max.trim());
    this.load();
  }

  toggleInStock(): void {
    this.inStockOnly.update((v) => !v);
    this.load();
  }

  toggleFilters(): void {
    this.filtersOpen.update((v) => !v);
  }

  clearFilters(): void {
    this.minPrice.set('');
    this.maxPrice.set('');
    this.inStockOnly.set(false);
    this.sort.set('relevance');
    this.updateUrl({ q: null, category: null });
  }

  /** Pushes q/category to the URL; the queryParamMap subscription reloads. */
  private updateUrl(changes: { q?: string | null; category?: string | null }): void {
    const queryParams: Record<string, string | null> = {};
    if ('q' in changes) {
      queryParams['q'] = changes.q ?? null;
    }
    if ('category' in changes) {
      queryParams['category'] = changes.category ?? null;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge',
    });
  }

  private load(): void {
    this.loading.set(true);
    this.errored.set(false);
    const filters: CatalogFilters = {
      q: this.activeQuery() || undefined,
      category: this.selectedCategory() || undefined,
      minPrice: this.minPrice() || undefined,
      maxPrice: this.maxPrice() || undefined,
      inStock: this.inStockOnly() || undefined,
      sort: this.sort(),
    };
    this.catalog.search(filters).subscribe({
      next: (items) => {
        this.products.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.products.set([]);
        this.loading.set(false);
        this.errored.set(true);
      },
    });
  }
}
