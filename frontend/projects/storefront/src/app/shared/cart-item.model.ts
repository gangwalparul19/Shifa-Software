import { CartLine, MAX_QUANTITY, MIN_QUANTITY, Money, Product, isValidQuantity } from 'core';

/** Re-exported quantity bounds / validation from the shared core cart logic. */
export { MIN_QUANTITY, MAX_QUANTITY, isValidQuantity };

/**
 * A cart or wishlist entry. Product details are snapshotted so the cart renders
 * (and survives a reload from localStorage) without re-fetching the catalog.
 *
 * <p>Extends the core {@link CartLine} (productId, salePrice, quantity) so the
 * pure cart/wishlist logic in {@code core} operates on it directly while the
 * snapshot fields (name, image, ...) are preserved.
 */
export interface CartItem extends CartLine {
  productId: number;
  sku: string;
  name: string;
  /** Sale price at the time of adding, as a fixed-scale decimal string. */
  salePrice: Money;
  mrp: Money;
  /** Resolved, storefront-servable image URL. */
  imageUrl: string;
  quantity: number;
}

/** Result of a cart/wishlist mutation, carrying a message for invalid input. */
export interface MutationResult {
  ok: boolean;
  message?: string;
}

/** Builds a snapshot entry (quantity defaults to 1) from a catalog product. */
export function toCartItem(product: Product, imageUrl: string, quantity = 1): CartItem {
  return {
    productId: product.id,
    sku: product.sku,
    name: product.name,
    salePrice: product.salePrice,
    mrp: product.mrp,
    imageUrl,
    quantity,
  };
}
