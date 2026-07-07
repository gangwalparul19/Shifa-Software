import { Injectable, computed, effect, inject, signal } from '@angular/core';
import {
  MAX_QUANTITY,
  MIN_QUANTITY,
  Money,
  Product,
  addToCart,
  cartItemCount,
  cartSubtotal,
  isValidQuantity,
  removeFromCart,
  setCartQuantity,
  AuthService,
} from 'core';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CartItem, MutationResult, toCartItem } from './cart-item.model';
import { productPrimaryImage, resolveImageUrl } from './product-image';
import { AccountApi, CartLineInput, CartProduct } from '../account/account-api.service';

const STORAGE_KEY = 'shifa.cart.v1';

/** Debounce window (ms) for coalescing rapid cart mutations before a server PUT. */
const SYNC_DEBOUNCE_MS = 400;

/**
 * Client-side cart state (Requirement 2) backed by Angular signals and
 * persisted to {@code localStorage}. Header badges bind to {@link itemCount}
 * and the cart page to {@link items} / {@link subtotal}.
 *
 * <p>All cart rules (quantity 1..999 validation, duplicate-line merging capped
 * at 999, subtotal/count derivation) live as pure functions in the shared
 * {@code core} library ({@code cart-logic}) so they are property-tested in
 * isolation; this service is a thin signal/localStorage adapter over them.
 *
 * <p><strong>Persistence:</strong> guests use {@code localStorage} unchanged;
 * when a customer signs in the service merges the local cart with their saved
 * server cart (union by product, taking the max quantity, clamped 1..999), PUTs
 * the merged result and mirrors the server response, so a cart follows the
 * customer across devices. While authenticated every mutation is synced to
 * {@code /api/account/cart} (debounced, best-effort — errors are swallowed so
 * the cart keeps working offline), and {@link clear} issues a DELETE. Mirrors
 * the {@code WishlistService} approach.
 */
@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly auth = inject(AuthService);
  private readonly accountApi = inject(AccountApi);
  private readonly _items = signal<CartItem[]>(this.restore());

  /** Guards the one-time local↔server merge per sign-in. */
  private syncedForSession = false;
  /** Pending debounced server-sync handle. */
  private syncTimer: ReturnType<typeof setTimeout> | null = null;

  /** Current cart lines (read-only signal). */
  readonly items = this._items.asReadonly();

  /** Total number of units across all lines — drives the header cart badge. */
  readonly itemCount = computed(() => cartItemCount(this._items()));

  /** Number of distinct product lines in the cart. */
  readonly lineCount = computed(() => this._items().length);

  /** Cart subtotal as a fixed-scale Money string (Req 2.2). */
  readonly subtotal = computed<Money>(() => cartSubtotal(this._items()));

  readonly isEmpty = computed(() => this._items().length === 0);

  constructor() {
    // React to auth changes: merge + load on sign-in, reset the guard on sign-out.
    effect(() => {
      const session = this.auth.session();
      if (session) {
        if (!this.syncedForSession) {
          this.syncedForSession = true;
          this.mergeAndLoad();
        }
      } else {
        this.syncedForSession = false;
      }
    });
  }

  /** True while a customer is signed in (cart is server-backed). */
  private get authenticated(): boolean {
    return this.auth.isAuthenticated();
  }

  /**
   * Adds `quantity` of `product` (Req 2.1, 2.7). If the product is already in
   * the cart the line quantity is increased and capped at 999. Rejects a
   * non-whole or out-of-range quantity, leaving the cart unchanged (Req 2.6).
   */
  add(product: Product, quantity = 1): MutationResult {
    const candidate = toCartItem(product, productPrimaryImage(product), quantity);
    const result = addToCart(this._items(), candidate);
    if (!result.ok) {
      return { ok: false, message: result.message };
    }
    this.commit(result.lines);
    return { ok: true };
  }

  /**
   * Sets an existing line's quantity to `quantity` (Req 2.2). A non-whole or
   * out-of-range value is rejected and the cart is left unchanged (Req 2.6).
   */
  updateQuantity(productId: number, quantity: number): MutationResult {
    const result = setCartQuantity(this._items(), productId, quantity);
    if (!result.ok) {
      return { ok: false, message: result.message };
    }
    this.commit(result.lines);
    return { ok: true };
  }

  /** Removes a line and updates subtotal / count (Req 2.3). */
  remove(productId: number): void {
    this.commit(removeFromCart(this._items(), productId));
  }

  /** Empties the cart (used after a successful checkout). */
  clear(): void {
    this.commit([]);
    if (this.authenticated) {
      this.accountApi.clearCart().pipe(catchError(() => of(void 0))).subscribe();
    }
  }

  /** True when a product is already present as a cart line. */
  has(productId: number): boolean {
    return this._items().some((item) => item.productId === productId);
  }

  /**
   * On sign-in, merge the local cart with the server cart (union by product,
   * taking the max quantity, clamped 1..999), PUT the merged result and mirror
   * the authoritative server response into the signal. Failures leave the local
   * cart intact so the feature degrades gracefully offline.
   */
  private mergeAndLoad(): void {
    this.accountApi
      .getCart()
      .pipe(catchError(() => of<CartProduct[] | null>(null)))
      .subscribe((serverCart) => {
        if (!serverCart) {
          // Couldn't read the server cart — best-effort push the local one.
          this.pushToServer(this.toLineInputs(this._items()));
          return;
        }
        const merged = this.mergeLineInputs(this._items(), serverCart);
        this.accountApi
          .saveCart(merged)
          .pipe(catchError(() => of<CartProduct[] | null>(null)))
          .subscribe((saved) => {
            if (saved) {
              this.setItems(saved.map((p) => this.fromServer(p)));
            }
          });
      });
  }

  /**
   * Builds the merged line inputs for sign-in: every product from either cart,
   * with the larger of the two quantities, clamped to the valid 1..999 range.
   */
  private mergeLineInputs(local: CartItem[], server: CartProduct[]): CartLineInput[] {
    const quantities = new Map<number, number>();
    const consider = (productId: number, quantity: number) => {
      const clamped = Math.min(Math.max(quantity, MIN_QUANTITY), MAX_QUANTITY);
      const current = quantities.get(productId) ?? 0;
      quantities.set(productId, Math.max(current, clamped));
    };
    for (const line of local) {
      consider(line.productId, line.quantity);
    }
    for (const line of server) {
      consider(line.productId, line.quantity);
    }
    return [...quantities.entries()].map(([productId, quantity]) => ({ productId, quantity }));
  }

  /** Maps a server cart product to a local snapshot line. */
  private fromServer(product: CartProduct): CartItem {
    return {
      productId: product.productId,
      sku: product.sku,
      name: product.name,
      salePrice: product.salePrice,
      mrp: product.mrp,
      imageUrl: resolveImageUrl(product.imageKey),
      quantity: Math.min(Math.max(product.quantity, MIN_QUANTITY), MAX_QUANTITY),
    };
  }

  /** The current lines as {@code {productId, quantity}} save payloads. */
  private toLineInputs(items: CartItem[]): CartLineInput[] {
    return items.map((item) => ({ productId: item.productId, quantity: item.quantity }));
  }

  /** Sets the signal and persists without triggering a server round-trip. */
  private setItems(items: CartItem[]): void {
    this._items.set(items);
    this.persist(items);
  }

  private commit(items: CartItem[]): void {
    this._items.set(items);
    this.persist(items);
    if (this.authenticated) {
      this.scheduleSync();
    }
  }

  /** Debounces a full-cart PUT so rapid quantity changes coalesce into one call. */
  private scheduleSync(): void {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer);
    }
    this.syncTimer = setTimeout(() => {
      this.syncTimer = null;
      this.pushToServer(this.toLineInputs(this._items()));
    }, SYNC_DEBOUNCE_MS);
  }

  /** Best-effort PUT of the full cart; errors are swallowed (offline-safe). */
  private pushToServer(items: CartLineInput[]): void {
    if (!this.authenticated) {
      return;
    }
    this.accountApi
      .saveCart(items)
      .pipe(catchError(() => of<CartProduct[] | null>(null)))
      .subscribe();
  }

  private persist(items: CartItem[]): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      // Storage may be unavailable (private mode); cart still works in-memory.
    }
  }

  private restore(): CartItem[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return [];
      }
      const parsed = JSON.parse(raw) as CartItem[];
      if (!Array.isArray(parsed)) {
        return [];
      }
      // Defensive: keep only well-formed, in-range lines.
      return parsed.filter(
        (item) =>
          item &&
          typeof item.productId === 'number' &&
          isValidQuantity(item.quantity),
      );
    } catch {
      return [];
    }
  }
}
