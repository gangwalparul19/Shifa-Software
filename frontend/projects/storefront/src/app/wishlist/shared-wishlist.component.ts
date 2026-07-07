import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Product, ProductVisibility } from 'core';
import { AccountApi, WishlistProduct } from '../account/account-api.service';
import { CartService } from '../shared/cart.service';
import { resolveImageUrl } from '../shared/product-image';
import { discountPercent, formatInr } from '../shared/money';

/**
 * Public, read-only view of a shared wishlist (route 'wishlist/shared/:token').
 *
 * <p>Needs no login: anyone with the link fetches the owner's saved products
 * ({@code GET /api/wishlist/shared/{token}}, product summaries only) and can add
 * them to their own cart. Handles loading, an invalid/revoked token, and an
 * empty list gracefully.
 */
@Component({
  selector: 'sf-shared-wishlist',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './shared-wishlist.component.html',
  styleUrl: './wishlist.component.css',
})
export class SharedWishlistComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly accountApi = inject(AccountApi);
  private readonly cart = inject(CartService);

  protected readonly loading = signal(true);
  protected readonly invalid = signal(false);
  protected readonly items = signal<WishlistProduct[]>([]);
  /** Product id most recently added to the cart (drives the "Added ✓" flash). */
  protected readonly addedId = signal<number | null>(null);

  constructor() {
    const token = this.route.snapshot.paramMap.get('token');
    if (!token) {
      this.loading.set(false);
      this.invalid.set(true);
      return;
    }
    this.accountApi.getSharedWishlist(token).subscribe({
      next: (products) => {
        this.items.set(products);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.invalid.set(true);
      },
    });
  }

  imageUrl(item: WishlistProduct): string {
    return resolveImageUrl(item.imageKey);
  }

  price(item: WishlistProduct): string {
    return formatInr(item.salePrice);
  }

  mrp(item: WishlistProduct): string {
    return formatInr(item.mrp);
  }

  discount(item: WishlistProduct): number {
    return discountPercent(item.mrp, item.salePrice);
  }

  /** Adds a shared product to the visitor's own cart (reuses {@link CartService}). */
  addToCart(item: WishlistProduct): void {
    const result = this.cart.add(this.asProduct(item), 1);
    if (result.ok) {
      this.addedId.set(item.productId);
      setTimeout(() => {
        if (this.addedId() === item.productId) {
          this.addedId.set(null);
        }
      }, 2000);
    }
  }

  /** Reconstructs a minimal published Product from a summary for cart insertion. */
  private asProduct(item: WishlistProduct): Product {
    return {
      id: item.productId,
      sku: item.sku,
      name: item.name,
      salePrice: item.salePrice,
      mrp: item.mrp,
      visibility: ProductVisibility.PUBLISHED,
      images: [{ id: 0, objectKey: item.imageKey ?? '', published: true, sortOrder: 0 }],
    };
  }
}
