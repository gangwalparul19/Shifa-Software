import { CartLine, addToCart, CartMutation } from '../cart/cart-logic';

/**
 * The minimal shape the pure wishlist logic needs: a product identity. The
 * storefront uses a richer entry type (product snapshot); the generic functions
 * below preserve those extra fields.
 */
export interface WishlistEntry {
  productId: number;
}

/** True when `productId` is already saved in the wishlist. */
export function isInWishlist(
  entries: readonly WishlistEntry[],
  productId: number,
): boolean {
  return entries.some((entry) => entry.productId === productId);
}

/**
 * Adds `entry` to the wishlist with set semantics: at most one entry per
 * product, so a duplicate add is a no-op (Req 2.4, 2.8).
 */
export function addToWishlist<T extends WishlistEntry>(
  entries: readonly T[],
  entry: T,
): T[] {
  if (isInWishlist(entries, entry.productId)) {
    return [...entries];
  }
  return [...entries, entry];
}

/** Removes `productId` from the wishlist. */
export function removeFromWishlist<T extends WishlistEntry>(
  entries: readonly T[],
  productId: number,
): T[] {
  return entries.filter((entry) => entry.productId !== productId);
}

/** Outcome of moving a wishlist item into the cart (Req 2.5). */
export interface MoveToCartResult<W extends WishlistEntry, C extends CartLine> {
  moved: boolean;
  wishlist: W[];
  cart: C[];
}

/**
 * Moves the wishlist product `productId` to the cart with quantity 1 and
 * removes it from the wishlist (Req 2.5). The cart line is built by
 * {@link toCartLine} from the wishlist entry. If the product is not in the
 * wishlist, nothing changes and {@code moved} is false.
 */
export function moveWishlistItemToCart<W extends WishlistEntry, C extends CartLine>(
  entries: readonly W[],
  cart: readonly C[],
  productId: number,
  toCartLine: (entry: W) => C,
): MoveToCartResult<W, C> {
  const entry = entries.find((e) => e.productId === productId);
  if (!entry) {
    return { moved: false, wishlist: [...entries], cart: [...cart] };
  }
  const line = { ...toCartLine(entry), quantity: 1 };
  const mutation: CartMutation<C> = addToCart(cart, line);
  return {
    moved: true,
    wishlist: removeFromWishlist(entries, productId),
    cart: mutation.lines,
  };
}
