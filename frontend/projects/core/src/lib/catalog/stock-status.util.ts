import { Product, StockStatus } from '../models/product.model';

/**
 * Presentation helpers for a product's derived {@link StockStatus} (Catalog &
 * Discovery). Kept pure so both storefront badges and the admin grid share one
 * source of truth for wording and purchasability.
 */

/** The effective stock status of a product, defaulting to in-stock when absent. */
export function stockStatusOf(product: Pick<Product, 'stockStatus'>): StockStatus {
  return product.stockStatus ?? StockStatus.IN_STOCK;
}

/** Whether a product can be added to the cart / purchased (not out of stock). */
export function isPurchasable(product: Pick<Product, 'stockStatus'>): boolean {
  return stockStatusOf(product) !== StockStatus.OUT_OF_STOCK;
}

/**
 * A short customer-facing stock badge label. For low stock it surfaces the
 * remaining quantity ("Only 3 left") when known, else a generic "Low stock".
 */
export function stockBadgeLabel(
  product: Pick<Product, 'stockStatus' | 'stockQuantity'>,
): string {
  switch (stockStatusOf(product)) {
    case StockStatus.OUT_OF_STOCK:
      return 'Out of stock';
    case StockStatus.LOW_STOCK:
      return typeof product.stockQuantity === 'number' && product.stockQuantity > 0
        ? `Only ${product.stockQuantity} left`
        : 'Low stock';
    case StockStatus.IN_STOCK:
    default:
      return 'In stock';
  }
}

/** A tone key for styling the badge (maps to CSS classes per surface). */
export function stockBadgeTone(
  product: Pick<Product, 'stockStatus'>,
): 'ok' | 'low' | 'out' {
  switch (stockStatusOf(product)) {
    case StockStatus.OUT_OF_STOCK:
      return 'out';
    case StockStatus.LOW_STOCK:
      return 'low';
    default:
      return 'ok';
  }
}
