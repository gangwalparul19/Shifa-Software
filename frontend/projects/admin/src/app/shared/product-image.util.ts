/**
 * Shared product-image resolution for the admin app.
 *
 * <p>Product image keys come from the backend `product_images.object_key`
 * column as RELATIVE web paths (e.g. `products/shifa-01.jpg`, no leading
 * slash) — not absolute http(s) URLs. The admin serves these assets from its
 * own `public/products/**` folder (globbed to the web root by angular.json),
 * so a relative key needs a leading slash to resolve to `/products/...`.
 *
 * These helpers centralise that rule so the products list, product detail
 * hero, and any future line-item thumbnail all resolve images the same way.
 */

/** Placeholder shown when a product has no usable image (served from public assets). */
export const PLACEHOLDER_PRODUCT_IMAGE = '/products/placeholder.svg';

/**
 * Resolves a stored image key to a browser-usable URL.
 *
 * <ul>
 *   <li>`http://` / `https://` → used as-is.</li>
 *   <li>Leading `/` → used as-is (already web-root relative).</li>
 *   <li>Anything else → prefixed with `/` (e.g. `products/shifa-01.jpg` →
 *       `/products/shifa-01.jpg`).</li>
 *   <li>Empty / null / undefined → the {@link PLACEHOLDER_PRODUCT_IMAGE}.</li>
 * </ul>
 */
export function resolveImageUrl(
  key: string | null | undefined,
  placeholder: string = PLACEHOLDER_PRODUCT_IMAGE,
): string {
  const k = (key ?? '').trim();
  if (!k) {
    return placeholder;
  }
  if (/^https?:\/\//i.test(k)) {
    return k;
  }
  if (k.startsWith('/')) {
    return k;
  }
  return `/${k}`;
}

/** Resolves the primary image URL for a product (its first image, else the placeholder). */
export function productImageUrl(
  product: { images?: readonly { objectKey: string }[] | null } | null | undefined,
  placeholder: string = PLACEHOLDER_PRODUCT_IMAGE,
): string {
  return resolveImageUrl(product?.images?.[0]?.objectKey, placeholder);
}

/**
 * `<img (error)>` handler: swaps a broken image out for the placeholder.
 * Guards against a loop when the placeholder itself fails to load.
 */
export function imageErrorFallback(
  event: Event,
  placeholder: string = PLACEHOLDER_PRODUCT_IMAGE,
): void {
  const img = event.target as HTMLImageElement | null;
  if (img && !img.src.endsWith(placeholder)) {
    img.src = placeholder;
  }
}
