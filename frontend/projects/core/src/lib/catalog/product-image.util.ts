import { ProductImage } from '../models/product.model';

/**
 * Default placeholder shown for a product that has no published image (Req 1.4).
 * Storefront ships this asset; callers may override it per surface.
 */
export const PLACEHOLDER_PRODUCT_IMAGE = '/assets/placeholder-product.svg';

/**
 * Selects the image to display for a product (Req 1.4).
 *
 * <p>Pure function so it can be property-tested in isolation (design:
 * correctness Property 17). The rule:
 * <ul>
 *   <li>If the product has at least one <em>published</em> image, return the
 *       object key of the published image with the lowest {@code sortOrder}
 *       (ties broken by input order — a stable sort).</li>
 *   <li>Otherwise return the {@code placeholder} — unpublished images are never
 *       shown to customers.</li>
 * </ul>
 *
 * @param images      the product's images (may be undefined/null/empty)
 * @param placeholder the placeholder to use when no published image exists
 * @returns the object key/URL to render
 */
export function selectProductImage(
  images: readonly ProductImage[] | undefined | null,
  placeholder: string = PLACEHOLDER_PRODUCT_IMAGE,
): string {
  const published = (images ?? []).filter((image) => image.published);
  if (published.length === 0) {
    return placeholder;
  }
  // Stable copy sorted by sortOrder ascending; the first is the primary image.
  const primary = published
    .map((image, index) => ({ image, index }))
    .sort((a, b) => a.image.sortOrder - b.image.sortOrder || a.index - b.index)[0].image;
  return primary.objectKey;
}

/** Whether a product would fall back to the placeholder image (Req 1.4). */
export function hasPublishedImage(
  images: readonly ProductImage[] | undefined | null,
): boolean {
  return (images ?? []).some((image) => image.published);
}
