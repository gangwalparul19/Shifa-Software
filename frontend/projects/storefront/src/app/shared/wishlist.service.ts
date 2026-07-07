import { Injectable, computed, effect, inject, signal } from '@angular/core';
import {
  AuthService,
  Product,
  ProductVisibility,
  addToWishlist,
  isInWishlist,
  moveWishlistItemToCart,
  removeFromWishlist,
} from 'core';
import { forkJoin, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { CartItem } from './cart-item.model';
import { CartService } from './cart.service';
import { AccountApi, WishlistProduct } from '../account/account-api.service';
import { productPrimaryImage, resolveImageUrl } from './product-image';

const STORAGE_KEY = 'shifa.wishlist.v1';

/**
 * Storefront wishlist state (Requirement 2.4, 2.5, 2.8) with set semantics:
 * a product appears at most once. Backed by signals; the header wishlist badge
 * binds to {@link count}.
 *
 * <p><strong>Persistence (Phase B):</strong> guests use {@code localStorage};
 * when a customer signs in the service migrates any locally-saved items to the
 * server (idempotent POSTs) and thereafter mirrors the server list, so a
 * wishlist follows the customer across devices. Add/remove update the local
 * signal immediately (optimistic) and, when authenticated, sync to
 * {@code /api/account/wishlist}. On sign-out it falls back to localStorage.
 *
 * <p>The set-semantics and move-to-cart rules live as pure functions in the
 * shared {@code core} library ({@code wishlist-logic}) and are property-tested
 * there; this service is the signal/persistence adapter over them.
 */
@Injectable({ providedIn: 'root' })
export class WishlistService {
  private readonly cart = inject(CartService);
  private readonly auth = inject(AuthService);
  private readonly accountApi = inject(AccountApi);
  private readonly _items = signal<CartItem[]>(this.restore());
  /** Guards the one-time local→server migration per sign-in. */
  private syncedForSession = false;

  readonly items = this._items.asReadonly();
  readonly count = computed(() => this._items().length);
  readonly isEmpty = computed(() => this._items().length === 0);

  constructor() {
    // React to auth changes: migrate + load on sign-in, reset on sign-out.
    effect(() => {
      const session = this.auth.session();
      if (session) {
        if (!this.syncedForSession) {
          this.syncedForSession = true;
          this.migrateAndLoad();
        }
      } else {
        this.syncedForSession = false;
      }
    });
  }

  /** True while a customer is signed in (wishlist is server-backed). */
  private get authenticated(): boolean {
    return this.auth.isAuthenticated();
  }

  /**
   * On sign-in, push any locally-saved products to the server (idempotent) then
   * replace local state with the authoritative server list. Failures leave the
   * local wishlist intact so the feature degrades gracefully offline.
   */
  private migrateAndLoad(): void {
    const local = this._items();
    const uploads = local.map((item) =>
      this.accountApi.addWishlist(item.productId).pipe(catchError(() => of(void 0))),
    );
    const seed = uploads.length ? forkJoin(uploads) : of([]);
    seed
      .pipe(
        switchMap(() => this.accountApi.listWishlist()),
        catchError(() => of(null)),
      )
      .subscribe((serverItems) => {
        if (serverItems) {
          this.commit(serverItems.map((p) => this.fromServer(p)));
        }
      });
  }

  /** Maps a server wishlist product summary to a local snapshot entry. */
  private fromServer(product: WishlistProduct): CartItem {
    return {
      productId: product.productId,
      sku: product.sku,
      name: product.name,
      salePrice: product.salePrice,
      mrp: product.mrp,
      imageUrl: resolveImageUrl(product.imageKey),
      quantity: 1,
    };
  }

  /** True when the product is already saved. */
  has(productId: number): boolean {
    return isInWishlist(this._items(), productId);
  }

  /**
   * Adds a product to the wishlist, keeping a single entry per product — a
   * duplicate add is a no-op (Req 2.4, 2.8).
   */
  add(product: Product): void {
    const entry = this.toEntry(product);
    this.commit(addToWishlist(this._items(), entry));
    if (this.authenticated) {
      this.accountApi.addWishlist(product.id).pipe(catchError(() => of(void 0))).subscribe();
    }
  }

  /** Removes a product from the wishlist. */
  remove(productId: number): void {
    this.commit(removeFromWishlist(this._items(), productId));
    if (this.authenticated) {
      this.accountApi.removeWishlist(productId).pipe(catchError(() => of(void 0))).subscribe();
    }
  }

  /** Adds when absent, removes when present; returns the resulting state. */
  toggle(product: Product): boolean {
    if (this.has(product.id)) {
      this.remove(product.id);
      return false;
    }
    this.add(product);
    return true;
  }

  /**
   * Moves a saved product to the cart with quantity 1 and removes it from the
   * wishlist (Req 2.5). Both the resulting cart and wishlist come from the pure
   * {@code moveWishlistItemToCart} reducer so the two stay consistent.
   */
  moveToCart(productId: number): void {
    const result = moveWishlistItemToCart(
      this._items(),
      this.cart.items(),
      productId,
      (entry) => entry,
    );
    if (!result.moved) {
      return;
    }
    // Reuse the CartService's add so its snapshot/image logic and persistence
    // stay authoritative, then apply the reduced wishlist.
    const entry = this._items().find((item) => item.productId === productId);
    if (entry) {
      this.cart.add(this.asProduct(entry), 1);
    }
    this.commit(result.wishlist);
    if (this.authenticated) {
      this.accountApi.removeWishlist(productId).pipe(catchError(() => of(void 0))).subscribe();
    }
  }

  clear(): void {
    this.commit([]);
  }

  /** Builds a wishlist snapshot entry from a catalog product. */
  private toEntry(product: Product): CartItem {
    return {
      productId: product.id,
      sku: product.sku,
      name: product.name,
      salePrice: product.salePrice,
      mrp: product.mrp,
      imageUrl: productPrimaryImage(product),
      quantity: 1,
    };
  }

  /** Reconstructs a minimal Product from a snapshot entry for cart insertion. */
  private asProduct(item: CartItem): Product {
    return {
      id: item.productId,
      sku: item.sku,
      name: item.name,
      salePrice: item.salePrice,
      mrp: item.mrp,
      visibility: ProductVisibility.PUBLISHED,
      images: [{ id: 0, objectKey: item.imageUrl, published: true, sortOrder: 0 }],
    };
  }

  private commit(items: CartItem[]): void {
    this._items.set(items);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      // Ignore storage failures; wishlist still works in-memory.
    }
  }

  private restore(): CartItem[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed = raw ? (JSON.parse(raw) as CartItem[]) : [];
      return Array.isArray(parsed)
        ? parsed.filter((item) => item && typeof item.productId === 'number')
        : [];
    } catch {
      return [];
    }
  }
}
