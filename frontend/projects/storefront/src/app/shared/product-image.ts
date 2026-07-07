import { Product, ProductImage, selectProductImage } from 'core';

/** Placeholder shown when a product has no published image (Req 1.4). */
export const STOREFRONT_PLACEHOLDER_IMAGE = '/products/placeholder.svg';

/**
 * Resolves a product image object key to a storefront-servable URL.
 *
 * <p>Seeded products carry object keys like {@code "products/shifa-01.jpg"}.
 * Those assets live in the storefront's own {@code public/products/} folder,
 * which Angular serves at the web root, so a key resolves to a root-relative
 * path such as {@code /products/shifa-01.jpg}. Absolute URLs (http/https/data)
 * and already-rooted paths pass through unchanged.
 */
export function resolveImageUrl(objectKey: string | null | undefined): string {
  const key = (objectKey ?? '').trim();
  if (!key) {
    return STOREFRONT_PLACEHOLDER_IMAGE;
  }
  if (/^(https?:\/\/|data:|\/)/i.test(key)) {
    return key;
  }
  return `/${key}`;
}

/**
 * Picks the primary published image for a product and resolves it to a
 * storefront URL, falling back to the placeholder (Req 1.4). Reuses the shared
 * {@link selectProductImage} rule from the core library.
 */
export function productImageUrl(images: readonly ProductImage[] | undefined | null): string {
  const key = selectProductImage(images, STOREFRONT_PLACEHOLDER_IMAGE);
  return resolveImageUrl(key);
}

/** Convenience for a whole {@link Product}. */
export function productPrimaryImage(product: Pick<Product, 'images'>): string {
  return productImageUrl(product.images);
}
